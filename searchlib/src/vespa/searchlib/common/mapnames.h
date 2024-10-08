// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include <string>

namespace search {

/**
 * A simple wrapper class for the named maps of properties.
 **/
struct MapNames
{
    /** name of rank feature property collection **/
    static const std::string RANK;

    /** name of feature override property collection **/
    static const std::string FEATURE;

    /** name of highlightterms property collection **/
    static const std::string HIGHLIGHTTERMS;

    /** name of match property collection **/
    static const std::string MATCH;

    /** name of cache property collection **/
    static const std::string CACHES;

    /** name of model property collection **/
    static const std::string MODEL;

    /** name of trace property collection **/
    static const std::string TRACE;
};

} // namespace search
