// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include <bit>

namespace vespalib::eval {

inline double hamming_distance(double a, double b) {
    uint8_t x = (uint8_t) (int8_t) a;
    uint8_t y = (uint8_t) (int8_t) b;
    return std::popcount(uint8_t(x ^ y));
}

}
