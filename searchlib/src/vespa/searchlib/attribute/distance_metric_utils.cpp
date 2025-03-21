// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "distance_metric_utils.h"
#include <vespa/vespalib/util/exceptions.h>

namespace search::attribute {

namespace {

const std::string euclidean = "euclidean";
const std::string angular = "angular";
const std::string geodegrees = "geodegrees";
const std::string innerproduct = "innerproduct";
const std::string prenormalized_angular = "prenormalized_angular";
const std::string dotproduct = "dotproduct";
const std::string hamming = "hamming";

}

std::string
DistanceMetricUtils::to_string(DistanceMetric metric)
{
    switch (metric) {
        case DistanceMetric::Euclidean: return euclidean;
        case DistanceMetric::Angular: return angular;
        case DistanceMetric::GeoDegrees: return geodegrees;
        case DistanceMetric::InnerProduct: return innerproduct;
        case DistanceMetric::Hamming: return hamming;
        case DistanceMetric::PrenormalizedAngular: return prenormalized_angular;
        case DistanceMetric::Dotproduct: return dotproduct;
    }
    throw vespalib::IllegalArgumentException("Unknown distance metric " + std::to_string(static_cast<int>(metric)));
}

DistanceMetric
DistanceMetricUtils::to_distance_metric(const std::string& metric)
{
    if (metric == euclidean) {
        return DistanceMetric::Euclidean;
    } else if (metric == angular) {
        return DistanceMetric::Angular;
    } else if (metric == geodegrees) {
        return DistanceMetric::GeoDegrees;
    } else if (metric == innerproduct) {
        return DistanceMetric::InnerProduct;
    } else if (metric == prenormalized_angular) {
        return DistanceMetric::PrenormalizedAngular;
    } else if (metric == dotproduct) {
        return DistanceMetric::Dotproduct;
    } else if (metric == hamming) {
        return DistanceMetric::Hamming;
    } else {
        throw vespalib::IllegalStateException("Unknown distance metric '" + metric + "'");
    }
}

}
