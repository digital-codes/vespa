# Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
vespa_add_executable(eval_typed_cells_test_app TEST
    SOURCES
    typed_cells_test.cpp
    DEPENDS
    vespaeval
    GTest::gtest
)
vespa_add_test(NAME eval_typed_cells_test_app COMMAND eval_typed_cells_test_app)
