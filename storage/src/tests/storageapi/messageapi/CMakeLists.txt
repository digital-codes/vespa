# Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
vespa_add_library(storageapi_testmessageapi
    SOURCES
    storage_message_address_test.cpp
    DEPENDS
    vespa_storage
    GTest::gtest
)
