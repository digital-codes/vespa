# Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
# @author Vegard Sjonfjell
# @author Eirik Nygaard
# @author Arnstein Ressem
cmake_minimum_required(VERSION 3.20 FATAL_ERROR)

include(functions.cmake)
list(APPEND CMAKE_MODULE_PATH
     "$ENV{HOME}/share/cmake/Modules"
     "/opt/vespa-deps/share/cmake/Modules"
     "${CMAKE_CURRENT_SOURCE_DIR}/cmake"
)
include(default_build_settings.cmake)
vespa_detect_build_platform()
message("-- Vespa build platform is ${VESPA_OS_DISTRO} ${VESPA_OS_DISTRO_VERSION}")
vespa_use_default_cxx_compiler()
vespa_use_default_java_home()

project(vespa CXX C)
vespa_use_default_vespa_unprivileged()
vespa_use_default_cmake_install_prefix()
include(GNUInstallDirs)
vespa_use_default_vespa_user()
vespa_use_default_vespa_group()
vespa_use_default_vespa_deps_prefix()
vespa_use_default_cmake_prefix_path()

SET(CMAKE_FIND_PACKAGE_SORT_ORDER NATURAL)
SET(CMAKE_FIND_PACKAGE_SORT_DIRECTION DEC)

find_package(Threads REQUIRED)

find_package(LLVM REQUIRED CONFIG)
message(STATUS "Found LLVM ${LLVM_PACKAGE_VERSION}")
message(STATUS "Using LLVMConfig.cmake in: ${LLVM_DIR}")
message(STATUS "LLVM_INCLUDE_DIRS is ${LLVM_INCLUDE_DIRS}")
message(STATUS "LLVM_LIBRARY_DIRS is ${LLVM_LIBRARY_DIRS}")
message(STATUS "LLVM_INCLUDE_DIR is ${LLVM_INCLUDE_DIR}")
message(STATUS "LLVM_MAIN_INCLUDE_DIR is ${LLVM_MAIN_INCLUDE_DIR}")
message(STATUS "LLVM_LIBRARY_DIR is ${LLVM_LIBRARY_DIR}")

vespa_use_default_build_settings()

# allows import of project in CLion on OSX
if (${CMAKE_SYSTEM_NAME} MATCHES "Darwin")
    set(CMAKE_THREAD_LIBS_INIT "-lpthread")
endif()

# TODO: Move this to where it's actually needed
find_package(JNI REQUIRED)

find_package(GTest REQUIRED)

find_package(Protobuf REQUIRED CONFIG)

include(build_settings.cmake)
include(cxx_settings.cmake)

# Enable CTest unit testing
enable_testing()

# Export vespa cmake config, to be used by other cmake project depending on vespa
set(VESPA_CMAKECONFIG_INSTALL_DIR ${CMAKE_INSTALL_DATAROOTDIR}/cmake/vespa)
string(REGEX REPLACE "-DV_TAG_COMPONENT=\\\\\"(.*)\\\\\"" "\\1" VESPA_VERSION ${V_TAG_COMPONENT})
message("-- VESPA_VERSION is ${VESPA_VERSION}")

include(CMakePackageConfigHelpers)
function(vespa_configure_exported_config)
  set(INCLUDE_INSTALL_DIR ${CMAKE_INSTALL_INCLUDEDIR})
  set(INSTALL_PREFIX_DIR ${CMAKE_INSTALL_PREFIX})
  set(CMAKECONFIG_DIR ${VESPA_CMAKECONFIG_INSTALL_DIR})
  configure_package_config_file(cmake/vespaConfig.cmake.in
    ${CMAKE_CURRENT_BINARY_DIR}/vespaConfig.cmake
    INSTALL_DESTINATION ${VESPA_CMAKECONFIG_INSTALL_DIR}
    PATH_VARS INCLUDE_INSTALL_DIR INSTALL_PREFIX_DIR CMAKECONFIG_DIR)
endfunction()
function(vespa_configure_exported_configversion)
  write_basic_package_version_file(${CMAKE_CURRENT_BINARY_DIR}/vespaConfigVersion.cmake
    VERSION ${VESPA_VERSION}
    COMPATIBILITY AnyNewerVersion)
endfunction()
vespa_configure_exported_config()
vespa_configure_exported_configversion()
install(FILES
  functions.cmake
  build_settings.cmake
  cxx_settings.cmake
  default_build_settings.cmake
  ${CMAKE_CURRENT_BINARY_DIR}/vespaConfig.cmake
  ${CMAKE_CURRENT_BINARY_DIR}/vespaConfigVersion.cmake
  cmake/vespaTargets.cmake
  DESTINATION ${VESPA_CMAKECONFIG_INSTALL_DIR})
# End of exporting vespa cmake config

vespa_install_data(valgrind-suppressions.txt etc/vespa)
vespa_install_data(tsan-suppressions.txt etc/vespa)
vespa_install_data(ubsan-suppressions.txt etc/vespa)

# Include vespa config definitions in every target
include_directories(BEFORE ${CMAKE_BINARY_DIR}/configdefinitions/src)

add_subdirectory(application-model)
add_subdirectory(client)
add_subdirectory(cloud-tenant-cd)
add_subdirectory(clustercontroller-apps)
add_subdirectory(clustercontroller-core)
add_subdirectory(clustercontroller-reindexer)
add_subdirectory(clustercontroller-utils)
add_subdirectory(config)
add_subdirectory(config-bundle)
add_subdirectory(configd)
add_subdirectory(configdefinitions)
add_subdirectory(config-model)
add_subdirectory(config-model-api)
add_subdirectory(config-model-fat)
add_subdirectory(config-provisioning)
add_subdirectory(config-proxy)
add_subdirectory(configserver)
add_subdirectory(configserver-flags)
add_subdirectory(configutil)
add_subdirectory(container-apache-http-client-bundle)
add_subdirectory(container-core)
add_subdirectory(container-disc)
add_subdirectory(container-messagebus)
add_subdirectory(container-onnxruntime)
add_subdirectory(container-llama)
add_subdirectory(container-search)
add_subdirectory(container-search-and-docproc)
add_subdirectory(container-spifly)
add_subdirectory(defaults)
add_subdirectory(docproc)
add_subdirectory(docprocs)
add_subdirectory(document)
add_subdirectory(documentapi)
add_subdirectory(eval)
add_subdirectory(fbench)
add_subdirectory(fileacquirer)
add_subdirectory(filedistribution)
add_subdirectory(flags)
add_subdirectory(fnet)
add_subdirectory(fsa)
add_subdirectory(hosted-zone-api)
add_subdirectory(http-client)
add_subdirectory(jdisc-cloud-aws)
add_subdirectory(jdisc_core)
add_subdirectory(jdisc-security-filters)
add_subdirectory(jrt_test)
add_subdirectory(linguistics)
add_subdirectory(linguistics-components)
add_subdirectory(lucene-linguistics)
add_subdirectory(logd)
add_subdirectory(logforwarder)
add_subdirectory(logserver)
add_subdirectory(lowercasing_test)
add_subdirectory(messagebus)
add_subdirectory(messagebus_test)
add_subdirectory(metrics)
add_subdirectory(metrics-proxy)
add_subdirectory(model-evaluation)
add_subdirectory(model-integration)
add_subdirectory(node-repository)
add_subdirectory(opennlp-linguistics)
add_subdirectory(orchestrator)
add_subdirectory(persistence)
add_subdirectory(predicate-search)
add_subdirectory(searchcore)
add_subdirectory(searchlib)
add_subdirectory(searchsummary)
add_subdirectory(security-utils)
add_subdirectory(service-monitor)
add_subdirectory(slobrok)
add_subdirectory(standalone-container)
add_subdirectory(storage)
add_subdirectory(storageserver)
add_subdirectory(streamingvisitors)
add_subdirectory(tenant-cd-api)
add_subdirectory(vbench)
add_subdirectory(vdslib)
add_subdirectory(vespa-3party-bundles)
add_subdirectory(vespa-3party-jars)
add_subdirectory(vespa-athenz)
add_subdirectory(vespabase)
add_subdirectory(vespaclient)
add_subdirectory(vespaclient-container-plugin)
add_subdirectory(vespaclient-core)
add_subdirectory(vespaclient-java)
add_subdirectory(vespa-feed-client)
add_subdirectory(vespa-feed-client-cli)
add_subdirectory(vespajlib)
add_subdirectory(vespalib)
add_subdirectory(vespalog)
add_subdirectory(vespamalloc)
add_subdirectory(vespa-osgi-testrunner)
add_subdirectory(vespa-testrunner-components)
add_subdirectory(zkfacade)
add_subdirectory(zookeeper-command-line-client)
add_subdirectory(zookeeper-server)

# Add any extra projects
add_extra_projects()

# Create module targets with name ${MODULE}+module depending on every target defined within that module
__create_module_targets(TARGETS "module")

# Create module targets with name ${MODULE}+test depending on every test target defined within that module
__create_module_targets(TEST_TARGETS "test")

# Create module targets with name ${MODULE}+source depending on every source target defined within that module
__create_module_targets(SOURCE_TARGETS "source")

# Create module targets with name ${MODULE}+object depending on every object target defined within that module
__create_module_targets(OBJECT_TARGETS "object")

# Create module source dependencies
__create_module_source_dependencies()
