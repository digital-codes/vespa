// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include <vespa/document/bucket/bucketid.h>
#include <vespa/storage/common/storagelink.h>
#include <vespa/config-persistence.h>
#include <vespa/config/helper/ifetchercallback.h>
#include <vespa/storage/common/servicelayercomponent.h>
#include <vespa/storage/persistence/messages.h>
#include <vespa/metrics/valuemetric.h>
#include <vespa/metrics/countmetric.h>
#include <vespa/metrics/metricset.h>
#include <vespa/vespalib/util/threadstackexecutor.h>
#include <atomic>
#include <vector>
#include <unordered_map>

namespace config {
    class ConfigUri;
    class ConfigFetcher;
}
namespace storage {

namespace lib {
    class ClusterState;
    class ClusterStateBundle;
    class Distribution;
    class DistributionConfigBundle;
}

/**
 * The changed bucket ownership handler is a storage link that synchronously
 * intercepts attempts to change the state on the node and ensure any
 * operations to buckets whose ownership is changed are aborted.
 *
 * If default config is used, all mutating ideal state operations for buckets
 * that--upon time of checking in this handler--belong to a different
 * distributor than the one specified as the sender will be aborted.
 *
 * We consider the following operations as mutating ideal state ops:
 *  - SplitBucketCommand
 *  - JoinBucketsCommand
 *  - MergeBucketsCommand (already blocked by throttler, but let's not
 *    let that stop us)
 *  - RemoveLocationCommand (technically an external load op, but is used by
 *    the GC functionality and must therefore be included here)
 *  - SetBucketStateCommand
 *  - DeleteBucketCommand
 *  - CreateBucketCommand
 *
 *  If default config is used, all mutating external operations with altered
 *  bucket owneship will also be aborted.
 *
 *  We consider the following external operations as mutating:
 *   - PutCommand
 *   - UpdateCommand
 *   - RemoveCommand
 *   - RevertCommand
 */
class ChangedBucketOwnershipHandler : public StorageLink {
public:
    class Metrics : public metrics::MetricSet {
    public:
        metrics::LongAverageMetric averageAbortProcessingTime;
        metrics::LongCountMetric idealStateOpsAborted;
        metrics::LongCountMetric externalLoadOpsAborted;

        explicit Metrics(metrics::MetricSet* owner = nullptr);
        ~Metrics() override;
    };

    /**
     * Wrapper around the distribution & state pairs that decides how to
     * compute the owner distributor for a bucket. It's possible to have
     * an ownership state with a nullptr cluster state when the node
     * initially starts up, which is why no ownership state must be used unless
     * invoking valid() on it returns true.
     */
    class OwnershipState {
        using BucketSpace = document::BucketSpace;
        std::shared_ptr<const lib::ClusterStateBundle> _state;
        std::shared_ptr<const lib::DistributionConfigBundle> _distributions;
    public:
        using SP = std::shared_ptr<OwnershipState>;
        using CSP = std::shared_ptr<const OwnershipState>;

        OwnershipState(std::shared_ptr<const lib::ClusterStateBundle> state,
                       std::shared_ptr<const lib::DistributionConfigBundle> distributions) noexcept;
        ~OwnershipState();

        static const uint16_t FAILED_TO_RESOLVE = 0xffff;

        [[nodiscard]] bool valid() const noexcept {
            return (_distributions && _state);
        }

        /**
         * Precondition: valid() == true.
         */
        const lib::ClusterState& getBaselineState() const;

        uint16_t ownerOf(const document::Bucket& bucket) const;
        bool storageNodeUp(document::BucketSpace bucketSpace, uint16_t nodeIndex) const;
    };

    /**
     * For unit testing only; trigger a reload of the cluster state from the
     * component registry, since tests may want to set the cluster state
     * explicitly without sending a message through the chain.
     */
    void reloadClusterState();

private:
    class ClusterStateSyncAndApplyTask;

    using PersistenceConfig = vespa::config::content::PersistenceConfig;
    using ClusterStateBundleCSP = std::shared_ptr<const lib::ClusterStateBundle>;

    ServiceLayerComponent         _component;
    Metrics                       _metrics;
    vespalib::ThreadStackExecutor _state_sync_executor;
    mutable std::mutex            _stateLock;
    ClusterStateBundleCSP         _currentState;
    OwnershipState::CSP           _currentOwnership;
    std::atomic<bool>             _abortQueuedAndPendingOnStateChange;
    std::atomic<bool>             _abortMutatingIdealStateOps;
    std::atomic<bool>             _abortMutatingExternalLoadOps;
    bool                          _receiving_distribution_config_from_cc;

    std::unique_ptr<AbortBucketOperationsCommand::AbortPredicate>
    makeLazyAbortPredicate(
            const OwnershipState::CSP& oldOwnership,
            const OwnershipState::CSP& newOwnership) const;

    static void logTransition(const lib::ClusterState& currentState,
                              const lib::ClusterState& newState);

    /**
     * Creates a new immutable OwnershipState based on the current distribution
     * and the provided cluster state and assigns it to _currentOwnership.
     */
    void setCurrentOwnershipWithStateNoLock(std::shared_ptr<const lib::ClusterStateBundle>);

    /**
     * Grabs _stateLock and returns a shared_ptr to the current ownership
     * state, which may or may not be valid().
     */
    OwnershipState::CSP getCurrentOwnershipState() const;

    bool isMutatingCommandAndNeedsChecking(const api::StorageMessage&) const;

    static bool isMutatingIdealStateOperation(const api::StorageMessage&);

    static bool isMutatingExternalOperation(const api::StorageMessage&);
    /**
     * Returns whether the operation in cmd has a bucket whose ownership in
     * the current cluster state does not match the distributor marked as
     * being the sender in the message itself.
     *
     * Precondition: cmd is an instance of a message type containing a bucket
     *     identifier.
     */
    bool sendingDistributorOwnsBucketInCurrentState(
            const api::StorageCommand& cmd) const;
    /**
     * Creates a reply for cmd, assigns an ABORTED return code and sends the
     * reply back up the storage chain.
     */
    void abortOperation(api::StorageCommand& cmd);

    /**
     * Returns whether aborting queued, changed ops and waiting for pending
     * changed ops is enabled through config.
     */
    bool enabledOperationAbortingOnStateChange() const;

    /**
     * Returns whether aborting outdated ideal state operations has been enabled
     * through config.
     */
    bool enabledIdealStateAborting() const;

    bool enabledExternalLoadAborting() const;

public:
    ChangedBucketOwnershipHandler(const PersistenceConfig& bootstrap_config,
                                  ServiceLayerComponentRegister& compReg);
    ~ChangedBucketOwnershipHandler() override;

    bool onSetSystemState(const std::shared_ptr<api::SetSystemStateCommand>&) override;
    bool onDown(const std::shared_ptr<api::StorageMessage>&) override;
    bool onInternalReply(const std::shared_ptr<api::InternalReply>& reply) override;
    void onClose() override;

    void on_configure(const PersistenceConfig&);

    /**
     * We want to ensure distribution config changes are thread safe wrt. our
     * own state, so we make sure to get notified when these happen so we can
     * do explicit locked updates.
     */
    void storageDistributionChanged() override;

    const Metrics& getMetrics() const { return _metrics; }
};

}
