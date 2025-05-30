// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include <cstdint>
#include <optional>

namespace search::fef {

/*
 * The gap between positions in adjacent elements in multi-value fields.
 */
using ElementGap = std::optional<uint32_t>;

}
