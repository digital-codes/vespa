# Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
vespa_add_executable(vespalib_process_memory_stats_test_app TEST
    SOURCES
    process_memory_stats_test.cpp
    DEPENDS
    vespalib
    GTest::gtest
)
vespa_add_test(NAME vespalib_process_memory_stats_test_app COMMAND vespalib_process_memory_stats_test_app)
