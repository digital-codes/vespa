// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include <vespa/searchcore/proton/initializer/initializer_task.h>
#include <vespa/searchcommon/common/growstrategy.h>
#include <string>

namespace proton { class DocumentMetaStore; }
namespace proton::documentmetastore {

/*
 * Class representing an Initializer task for loading document meta store
 * from disk to memory during proton startup.
 */
class DocumentMetaStoreInitializer : public initializer::InitializerTask
{
    std::string                    _baseDir;
    std::string                    _subDbName;
    std::string                    _docTypeName;
    std::shared_ptr<DocumentMetaStore>  _dms;

public:
    using SP = std::shared_ptr<DocumentMetaStoreInitializer>;

    // Note: lifetime of result must be handled by caller.
    DocumentMetaStoreInitializer(const std::string baseDir,
                                 const std::string &subDbName,
                                 const std::string &docTypeName,
                                 std::shared_ptr<DocumentMetaStore> dms);
    void run() override;
};


}
