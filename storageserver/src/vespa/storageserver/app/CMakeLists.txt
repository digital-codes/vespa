# Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
vespa_add_library(storageserver_storageapp STATIC
    SOURCES
    process.cpp
    distributorprocess.cpp
    servicelayerprocess.cpp
    dummyservicelayerprocess.cpp
    DEPENDS
    vespa_storage
)
