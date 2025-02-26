// This file is licensed under the Elastic License 2.0. Copyright 2021 StarRocks Limited.

#include "sorted_chunks_merger.h"

#include "exec/sort_exec_exprs.h"

namespace starrocks::vectorized {

SortedChunksMerger::SortedChunksMerger(bool is_pipeline) : _is_pipeline(is_pipeline) {}

SortedChunksMerger::~SortedChunksMerger() {}

Status SortedChunksMerger::init(const ChunkSuppliers& chunk_suppliers, const ChunkProbeSuppliers& chunk_probe_suppliers,
                                const ChunkHasSuppliers& chunk_has_suppliers,
                                const std::vector<ExprContext*>* sort_exprs, const std::vector<bool>* is_asc,
                                const std::vector<bool>* is_null_first) {
    if (chunk_suppliers.size() == 1) {
        _single_supplier = chunk_suppliers[0];
        _single_probe_supplier = chunk_probe_suppliers[0];
        _single_has_supplier = chunk_has_suppliers[0];
    } else {
        _cursors.reserve(chunk_suppliers.size());
        _min_heap.reserve(chunk_suppliers.size());
        for (int i = 0; i < chunk_suppliers.size(); ++i) {
            _cursors.emplace_back(std::make_unique<ChunkCursor>(chunk_suppliers[i], chunk_probe_suppliers[i],
                                                                chunk_has_suppliers[i], sort_exprs, is_asc,
                                                                is_null_first, _is_pipeline));
            ChunkCursor* cursor = _cursors.rbegin()->get();
            cursor->next();
            if (cursor->is_valid()) {
                _min_heap.push_back(cursor);
            }
        }
        std::make_heap(_min_heap.begin(), _min_heap.end(), _cursor_cmp_greater);
    }
    return Status::OK();
}

Status SortedChunksMerger::init_for_pipeline(const ChunkSuppliers& chunk_suppliers,
                                             const ChunkProbeSuppliers& chunk_probe_suppliers,
                                             const ChunkHasSuppliers& chunk_has_suppliers,
                                             const std::vector<ExprContext*>* sort_exprs,
                                             const std::vector<bool>* is_asc, const std::vector<bool>* is_null_first) {
    if (chunk_suppliers.size() == 1) {
        _single_supplier = chunk_suppliers[0];
        _single_probe_supplier = chunk_probe_suppliers[0];
        _single_has_supplier = chunk_has_suppliers[0];
    }

    _cursors.reserve(chunk_suppliers.size());
    for (int i = 0; i < chunk_suppliers.size(); ++i) {
        _cursors.emplace_back(std::make_unique<ChunkCursor>(chunk_suppliers[i], chunk_probe_suppliers[i],
                                                            chunk_has_suppliers[i], sort_exprs, is_asc, is_null_first,
                                                            _is_pipeline));
    }
    return Status::OK();
}

bool SortedChunksMerger::is_data_ready() {
    if (_cursors.size() == 1) {
        return _cursors[0]->chunk_has_supplier();
    } else {
        if (!_after_min_heap) {
            for (auto& cursor : _cursors) {
                if (!cursor->chunk_has_supplier()) {
                    return false;
                }
            }
            init_for_min_heap();
            return true;
        } else {
            // if wait for data, we should probe next row;
            // else because we have move to next row, so just test min_heap.
            if (_wait_for_data) {
                return _cursor->has_next() || _cursor->chunk_has_supplier();
            } else {
                return !_min_heap.empty();
            }
        }
    }
}

void SortedChunksMerger::init_for_min_heap() {
    if (_cursors.size() > 1) {
        _min_heap.reserve(_cursors.size());
        for (auto& cursor_ptr : _cursors) {
            ChunkCursor* cursor = cursor_ptr.get();
            cursor->reset_with_next_chunk_for_pipeline();
            cursor->next_for_pipeline();
            if (cursor->is_valid()) {
                _min_heap.push_back(cursor);
            }
        }
        std::make_heap(_min_heap.begin(), _min_heap.end(), _cursor_cmp_greater);
    }
    _after_min_heap = true;
}

void SortedChunksMerger::set_profile(RuntimeProfile* profile) {
    _total_timer = ADD_TIMER(profile, "MergeSortedChunks");
}

Status SortedChunksMerger::get_next(ChunkPtr* chunk, bool* eos) {
    ScopedTimer<MonotonicStopWatch> timer(_total_timer);

    DCHECK(chunk != nullptr);
    if (_min_heap.empty() && !_single_supplier) {
        *eos = true;
        *chunk = nullptr;
        return Status::OK();
    }

    // single source
    if (_single_supplier) {
        Chunk* tmp_chunk = nullptr;
        Status status = _single_supplier(&tmp_chunk);
        *eos = (tmp_chunk == nullptr);
        (*chunk).reset(tmp_chunk);
        return status;
    }

    // multiple sources
    *eos = false;
    ChunkCursor* cursor = _min_heap[0];
    *chunk = cursor->clone_empty_chunk(config::vector_chunk_size);

    ChunkPtr current_chunk = cursor->get_current_chunk();
    std::vector<uint32_t> selective_values; // for append_selective call
    selective_values.reserve(config::vector_chunk_size);
    selective_values.push_back(cursor->get_current_position_in_chunk());
    size_t row_number = 1;

    std::pop_heap(_min_heap.begin(), _min_heap.end(), _cursor_cmp_greater);
    cursor->next();
    if (cursor->is_valid()) {
        std::push_heap(_min_heap.begin(), _min_heap.end(), _cursor_cmp_greater);
    } else {
        _min_heap.pop_back();
    }

    while (row_number < config::vector_chunk_size && !_min_heap.empty()) {
        cursor = _min_heap[0];
        const auto& ptr = cursor->get_current_chunk();
        if (current_chunk == ptr) {
            selective_values.push_back(cursor->get_current_position_in_chunk());
        } else {
            (*chunk)->append_selective(*current_chunk, selective_values.data(), 0, selective_values.size());
            current_chunk = ptr;
            selective_values.clear();
            selective_values.push_back(cursor->get_current_position_in_chunk());
        }

        std::pop_heap(_min_heap.begin(), _min_heap.end(), _cursor_cmp_greater);
        cursor->next();
        if (cursor->is_valid()) {
            std::push_heap(_min_heap.begin(), _min_heap.end(), _cursor_cmp_greater);
        } else {
            _min_heap.pop_back();
        }

        ++row_number;
    }

    (*chunk)->append_selective(*current_chunk, selective_values.data(), 0, selective_values.size());
    (*chunk)->set_num_rows(row_number); // set constant column in chunk with right size.

    return Status::OK();
}

Status SortedChunksMerger::get_next_for_pipeline(ChunkPtr* chunk, std::atomic<bool>* eos, bool* should_exit) {
    ScopedTimer<MonotonicStopWatch> timer(_total_timer);

    DCHECK(chunk != nullptr);
    *chunk = std::make_shared<Chunk>();
    if (_min_heap.empty() && !_single_probe_supplier) {
        *eos = true;
        return Status::OK();
    }

    // single source
    if (_single_probe_supplier) {
        Chunk* tmp_chunk = nullptr;
        if (_single_has_supplier()) {
            *eos = !_single_probe_supplier(&tmp_chunk);
        } else {
            *should_exit = true;
        }
        (*chunk).reset(tmp_chunk);
        return Status::OK();
    }

    /*
     * Because compute thread couldn't blocking in pipeline, and merge-sort is
     * accept chunks from network in default, so if chunk hasn't come compute thread
     * should exit this operator and come back when you have data.
     * STEP 0 as the process to collect merged result.
     * STEP 1 as the process to drive cursor move to next row then execute STEP 0.
     * STEP 2 is almost like STEP 1 except it is executed when data is ready. 
     * 
     */
    for (;;) {
        // STEP 2:
        // move to next row
        if (_wait_for_data) {
            _wait_for_data = false;
            move_cursor_and_adjust_min_heap(eos);
            if (_row_number >= config::vector_chunk_size || _min_heap.empty()) {
                collect_merged_chunks(chunk);
                break;
            }
        }

        // STEP 0:
        // Guarantee: min_heap is keep min heap property, and it isn't empty.
        _cursor = _min_heap[0];
        if (!_row_number) {
            _result_chunk = _cursor->clone_empty_chunk(config::vector_chunk_size);
            _current_chunk = _cursor->get_current_chunk();
            _selective_values.clear();
            _selective_values.reserve(config::vector_chunk_size);
            _selective_values.push_back(_cursor->get_current_position_in_chunk());
        } else {
            const auto& ptr = _cursor->get_current_chunk();
            // If it is the same chunk, we just add a index for this row.
            // else we copy These datas, and record a new chunk.
            if (_current_chunk == ptr) {
                _selective_values.push_back(_cursor->get_current_position_in_chunk());
            } else {
                _result_chunk->append_selective(*_current_chunk, _selective_values.data(), 0, _selective_values.size());
                _current_chunk = ptr;
                _selective_values.clear();
                _selective_values.push_back(_cursor->get_current_position_in_chunk());
            }
        }

        ++_row_number;
        // we move min-element in the heap and then probe next row in cursor.
        std::pop_heap(_min_heap.begin(), _min_heap.end(), _cursor_cmp_greater);
        _wait_for_data = true;

        // probe next row.
        if (!_cursor->has_next() && !_cursor->chunk_has_supplier()) {
            *should_exit = true;
            break;
        } else {
            // STEP 1:
            // move to next row
            _wait_for_data = false;
            move_cursor_and_adjust_min_heap(eos);
            if (_row_number >= config::vector_chunk_size || _min_heap.empty()) {
                collect_merged_chunks(chunk);
                break;
            }
        }
    }

    return Status::OK();
}

void SortedChunksMerger::move_cursor_and_adjust_min_heap(std::atomic<bool>* eos) {
    // It has next row, so we move cursor.
    _cursor->next_for_pipeline();
    if (_cursor->is_valid()) {
        // we should adjust min_heap to keep min heap property.
        std::push_heap(_min_heap.begin(), _min_heap.end(), _cursor_cmp_greater);
    } else {
        // just remove one source.
        _min_heap.pop_back();
        *eos = _min_heap.empty();
    }
}
void SortedChunksMerger::collect_merged_chunks(ChunkPtr* chunk) {
    _result_chunk->append_selective(*_current_chunk, _selective_values.data(), 0, _selective_values.size());
    _result_chunk->set_num_rows(_row_number); // set constant column in chunk with right size.
    (*chunk) = std::move(_result_chunk);
    _row_number = 0;
}

} // namespace starrocks::vectorized