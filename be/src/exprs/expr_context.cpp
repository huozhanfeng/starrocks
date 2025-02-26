// This file is made available under Elastic License 2.0.
// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/be/src/exprs/expr_context.cpp

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#include "exprs/expr_context.h"

#include <gperftools/profiler.h>

#include <sstream>

#include "exprs/anyval_util.h"
#include "exprs/expr.h"
#include "exprs/slot_ref.h"
#include "exprs/vectorized/column_ref.h"
#include "runtime/mem_pool.h"
#include "runtime/mem_tracker.h"
#include "runtime/raw_value.h"
#include "runtime/runtime_state.h"
#include "udf/udf_internal.h"
#include "util/debug_util.h"
#include "util/stack_util.h"

namespace starrocks {

// Our new vectorized query executor is more powerful and stable than old query executor,
// The executor query executor related codes could be deleted safely.
// TODO: Remove old query executor related codes before 2021-09-30

ExprContext::ExprContext(Expr* root)
        : _fn_contexts_ptr(NULL), _root(root), _is_clone(false), _prepared(false), _opened(false), _closed(false) {}

ExprContext::~ExprContext() {
    DCHECK(!_prepared || _closed) << ". expr context address = " << this;
    for (int i = 0; i < _fn_contexts.size(); ++i) {
        delete _fn_contexts[i];
    }
}

// TODO(zc): memory tracker
Status ExprContext::prepare(RuntimeState* state, const RowDescriptor& row_desc, MemTracker* tracker) {
    if (_prepared) {
        return Status::OK();
    }
    DCHECK(tracker != NULL) << std::endl << get_stack_trace();
    DCHECK(_pool.get() == NULL);
    _prepared = true;
    // TODO: use param tracker to replace instance_mem_tracker
    // _pool.reset(new MemPool(new MemTracker(-1)));
    _pool.reset(new MemPool(state->instance_mem_tracker()));
    return _root->prepare(state, row_desc, this);
}

Status ExprContext::open(RuntimeState* state) {
    DCHECK(_prepared);
    if (_opened) {
        return Status::OK();
    }
    _opened = true;
    // Fragment-local state is only initialized for original contexts. Clones inherit the
    // original's fragment state and only need to have thread-local state initialized.
    FunctionContext::FunctionStateScope scope =
            _is_clone ? FunctionContext::THREAD_LOCAL : FunctionContext::FRAGMENT_LOCAL;
    return _root->open(state, this, scope);
}

Status ExprContext::open(std::vector<ExprContext*> evals, RuntimeState* state) {
    for (int i = 0; i < evals.size(); ++i) {
        RETURN_IF_ERROR(evals[i]->open(state));
    }
    return Status::OK();
}

void ExprContext::close(RuntimeState* state) {
    if (_closed) {
        return;
    }
    _closed = true;
    FunctionContext::FunctionStateScope scope =
            _is_clone ? FunctionContext::THREAD_LOCAL : FunctionContext::FRAGMENT_LOCAL;
    _root->close(state, this, scope);

    for (int i = 0; i < _fn_contexts.size(); ++i) {
        _fn_contexts[i]->impl()->close();
    }
    // _pool can be nullptr if Prepare() was never called
    if (_pool != nullptr) {
        _pool->free_all();
    }
}

int ExprContext::register_func(RuntimeState* state, const starrocks_udf::FunctionContext::TypeDesc& return_type,
                               const std::vector<starrocks_udf::FunctionContext::TypeDesc>& arg_types,
                               int varargs_buffer_size) {
    _fn_contexts.push_back(FunctionContextImpl::create_context(state, _pool.get(), return_type, arg_types,
                                                               varargs_buffer_size, false));
    _fn_contexts_ptr = &_fn_contexts[0];
    return _fn_contexts.size() - 1;
}

Status ExprContext::clone(RuntimeState* state, ExprContext** new_ctx) {
    DCHECK(_prepared);
    DCHECK(_opened);
    DCHECK(*new_ctx == NULL);

    *new_ctx = state->obj_pool()->add(new ExprContext(_root));
    (*new_ctx)->_pool.reset(new MemPool(_pool->mem_tracker()));
    for (int i = 0; i < _fn_contexts.size(); ++i) {
        (*new_ctx)->_fn_contexts.push_back(_fn_contexts[i]->impl()->clone((*new_ctx)->_pool.get()));
    }
    (*new_ctx)->_fn_contexts_ptr = &((*new_ctx)->_fn_contexts[0]);

    (*new_ctx)->_is_clone = true;
    (*new_ctx)->_prepared = true;
    (*new_ctx)->_opened = true;

    return _root->open(state, *new_ctx, FunctionContext::THREAD_LOCAL);
}

Status ExprContext::clone(RuntimeState* state, ExprContext** new_ctx, Expr* root) {
    DCHECK(_prepared);
    DCHECK(_opened);
    DCHECK(*new_ctx == NULL);

    *new_ctx = state->obj_pool()->add(new ExprContext(root));
    (*new_ctx)->_pool.reset(new MemPool(_pool->mem_tracker()));
    for (int i = 0; i < _fn_contexts.size(); ++i) {
        (*new_ctx)->_fn_contexts.push_back(_fn_contexts[i]->impl()->clone((*new_ctx)->_pool.get()));
    }
    (*new_ctx)->_fn_contexts_ptr = &((*new_ctx)->_fn_contexts[0]);

    (*new_ctx)->_is_clone = true;
    (*new_ctx)->_prepared = true;
    (*new_ctx)->_opened = true;

    return root->open(state, *new_ctx, FunctionContext::THREAD_LOCAL);
}

void ExprContext::free_local_allocations() {
    free_local_allocations(_fn_contexts);
}

void ExprContext::free_local_allocations(const std::vector<ExprContext*>& ctxs) {
    for (int i = 0; i < ctxs.size(); ++i) {
        ctxs[i]->free_local_allocations();
    }
}

void ExprContext::free_local_allocations(const std::vector<FunctionContext*>& fn_ctxs) {
    for (int i = 0; i < fn_ctxs.size(); ++i) {
        if (fn_ctxs[i]->impl()->closed()) {
            continue;
        }
        fn_ctxs[i]->impl()->free_local_allocations();
    }
}

void ExprContext::get_value(TupleRow* row, bool as_ascii, TColumnValue* col_val) {}

void* ExprContext::get_value(TupleRow* row) {
    return nullptr;
}

bool ExprContext::is_nullable() {
    if (_root->is_slotref()) {
        return SlotRef::is_nullable(_root);
    }
    return false;
}

BooleanVal ExprContext::get_boolean_val(TupleRow* row) {
    return _root->get_boolean_val(this, row);
}

TinyIntVal ExprContext::get_tiny_int_val(TupleRow* row) {
    return _root->get_tiny_int_val(this, row);
}

SmallIntVal ExprContext::get_small_int_val(TupleRow* row) {
    return _root->get_small_int_val(this, row);
}

IntVal ExprContext::get_int_val(TupleRow* row) {
    return _root->get_int_val(this, row);
}

BigIntVal ExprContext::get_big_int_val(TupleRow* row) {
    return _root->get_big_int_val(this, row);
}

FloatVal ExprContext::get_float_val(TupleRow* row) {
    return _root->get_float_val(this, row);
}

DoubleVal ExprContext::get_double_val(TupleRow* row) {
    return _root->get_double_val(this, row);
}

StringVal ExprContext::get_string_val(TupleRow* row) {
    return _root->get_string_val(this, row);
}

DateTimeVal ExprContext::get_datetime_val(TupleRow* row) {
    return _root->get_datetime_val(this, row);
}

DecimalVal ExprContext::get_decimal_val(TupleRow* row) {
    return _root->get_decimal_val(this, row);
}

DecimalV2Val ExprContext::get_decimalv2_val(TupleRow* row) {
    return _root->get_decimalv2_val(this, row);
}

Status ExprContext::get_const_value(RuntimeState* state, Expr& expr, AnyVal** const_val) {
    return Status::OK();
}

Status ExprContext::get_error(int start_idx, int end_idx) const {
    DCHECK(_opened);
    end_idx = end_idx == -1 ? _fn_contexts.size() : end_idx;
    DCHECK_GE(start_idx, 0);
    DCHECK_LE(end_idx, _fn_contexts.size());
    for (int idx = start_idx; idx < end_idx; ++idx) {
        DCHECK_LT(idx, _fn_contexts.size());
        FunctionContext* fn_ctx = _fn_contexts[idx];
        if (fn_ctx->has_error()) return Status::InternalError(fn_ctx->error_msg());
    }
    return Status::OK();
}

std::string ExprContext::get_error_msg() const {
    for (auto fn_ctx : _fn_contexts) {
        if (fn_ctx->has_error()) {
            return std::string(fn_ctx->error_msg());
        }
    }
    return "";
}

void ExprContext::clear_error_msg() {
    for (auto fn_ctx : _fn_contexts) {
        fn_ctx->clear_error_msg();
    }
}

ColumnPtr ExprContext::evaluate(vectorized::Chunk* chunk) {
    return evaluate(_root, chunk);
}

ColumnPtr ExprContext::evaluate(Expr* e, vectorized::Chunk* chunk) {
#ifndef NDEBUG
    if (chunk != nullptr) {
        chunk->check_or_die();
        CHECK(!chunk->is_empty());
    }
#endif
    auto ptr = e->evaluate(this, chunk);
    DCHECK(ptr != nullptr);
    if (chunk != nullptr && 0 != chunk->num_columns() && ptr->is_constant()) {
        ptr->resize(chunk->num_rows());
    }
    return ptr;
}

} // namespace starrocks
