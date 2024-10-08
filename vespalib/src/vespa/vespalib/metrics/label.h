// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include "handle.h"
#include <string>

namespace vespalib::metrics {

using LabelValue = std::string;

struct LabelTag {};

/**
 * Opaque handle representing an unique label value.
 **/
struct Label : Handle<LabelTag>
{
    explicit Label(size_t id) : Handle(id) {}
    static Label from_value(const std::string& value);
    const std::string& as_value() const;
};

} // namespace vespalib::metrics
