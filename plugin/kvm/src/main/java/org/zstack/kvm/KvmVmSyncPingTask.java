package org.zstack.kvm;

import org.springframework.beans.factory.annotation.Autowired;
import org.zstack.compute.vm.VmTracer;
import org.zstack.core.cloudbus.CloudBus;
import org.zstack.core.cloudbus.CloudBusCallBack;
import org.zstack.core.db.DatabaseFacade;
import org.zstack.core.db.SimpleQuery;
import org.zstack.core.db.SimpleQuery.Op;
import org.zstack.core.errorcode.ErrorFacade;
import org.zstack.header.Component;
import org.zstack.header.core.Completion;
import org.zstack.header.core.FutureCompletion;
import org.zstack.header.core.NopeCompletion;
import org.zstack.header.errorcode.ErrorCode;
import org.zstack.header.errorcode.OperationFailureException;
import org.zstack.header.host.*;
import org.zstack.header.message.MessageReply;
import org.zstack.header.rest.JsonAsyncRESTCallback;
import org.zstack.header.rest.RESTFacade;
import org.zstack.header.rest.SyncHttpCallHandler;
import org.zstack.header.vm.*;
import org.zstack.kvm.KVMAgentCommands.ReportVmStateCmd;
import org.zstack.kvm.KVMAgentCommands.VmSyncCmd;
import org.zstack.kvm.KVMAgentCommands.VmSyncResponse;
import org.zstack.kvm.KVMConstant.KvmVmState;
import org.zstack.utils.Utils;
import org.zstack.utils.logging.CLogger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.zstack.utils.CollectionDSL.e;
import static org.zstack.utils.CollectionDSL.map;

public class KvmVmSyncPingTask extends VmTracer implements HostPingTaskExtensionPoint, KVMHostConnectExtensionPoint,
        HostConnectionReestablishExtensionPoint, HostAfterConnectedExtensionPoint, Component {
    private static final CLogger logger = Utils.getLogger(KvmVmSyncPingTask.class);
    
    @Autowired
    private RESTFacade restf;
    @Autowired
    private KVMHostFactory factory;
    @Autowired
    private ErrorFacade errf;
    @Autowired
    private DatabaseFacade dbf;
    @Autowired
    private CloudBus bus;

    private void syncVm(final KVMHostContext host, final Completion completion) {
        VmSyncCmd cmd = new VmSyncCmd();
        restf.asyncJsonPost(host.buildUrl(KVMConstant.KVM_VM_SYNC_PATH), cmd, new JsonAsyncRESTCallback<VmSyncResponse>() {
            @Override
            public void fail(ErrorCode err) {
                logger.warn(String.format("unable to do vm sync on host[uuid:%s, ip:%s] because %s", host.getInventory().getUuid(), host.getInventory().getManagementIp(), err));
                completion.fail(err);
            }

            @Override
            public void success(VmSyncResponse ret) {
                if (ret.isSuccess()) {
                    Map<String, VmInstanceState> states = new HashMap<String, VmInstanceState>(ret.getStates().size());
                    for (Map.Entry<String, String> e : ret.getStates().entrySet()) {
                        VmInstanceState state = KvmVmState.valueOf(e.getValue()).toVmInstanceState();
                        if (state == VmInstanceState.Running || state == VmInstanceState.Unknown) {
                            states.put(e.getKey(), state);
                        }
                    }

                    reportVmState(host.getInventory().getUuid(), states);
                    completion.success();
                } else {
                    ErrorCode errorCode = errf.stringToOperationError(String.format("unable to do vm sync on host[uuid:%s, ip:%s] because %s", host.getInventory().getUuid(), host.getInventory().getManagementIp(), ret.getError()));
                    logger.warn(errorCode.toString());
                    completion.fail(errorCode);
                }
            }

            @Override
            public Class<VmSyncResponse> getReturnClass() {
                return VmSyncResponse.class;
            }

        }, TimeUnit.SECONDS, 300);
    }

    @Override
    public void executeTaskAlongWithPingTask(final HostInventory inv) {
        if (!KVMGlobalConfig.VM_SYNC_ON_HOST_PING.value(Boolean.class)) {
            return;
        }

        KVMHostContext host = factory.getHostContext(inv.getUuid());
        syncVm(host, new NopeCompletion());
    }

    @Override
    public HypervisorType getHypervisorType() {
        return HypervisorType.valueOf(KVMConstant.KVM_HYPERVISOR_TYPE);
    }

    @Override
    public void kvmHostConnected(KVMHostConnectedContext context) throws KVMHostConnectException {
        FutureCompletion completion = new FutureCompletion();
        syncVm(context, completion);
        completion.await();
        if (completion.getErrorCode() != null) {
            throw new OperationFailureException(completion.getErrorCode());
        }
    }

    @Override
    public void connectionReestablished(HostInventory inv) throws HostException {
        syncVm(factory.getHostContext(inv.getUuid()), new NopeCompletion());
    }

    @Override
    public HypervisorType getHypervisorTypeForReestablishExtensionPoint() {
        return HypervisorType.valueOf(KVMConstant.KVM_HYPERVISOR_TYPE);
    }

    @Override
    public boolean start() {
        restf.registerSyncHttpCallHandler(KVMConstant.KVM_REPORT_VM_STATE, ReportVmStateCmd.class, new SyncHttpCallHandler<ReportVmStateCmd>() {
            @Override
            public String handleSyncHttpCall(ReportVmStateCmd cmd) {
                VmInstanceState state = KvmVmState.valueOf(cmd.vmState).toVmInstanceState();

                SimpleQuery<VmInstanceVO> q = dbf.createQuery(VmInstanceVO.class);
                q.select(VmInstanceVO_.state);
                q.add(VmInstanceVO_.uuid, Op.EQ, cmd.vmUuid);
                VmInstanceState stateInDb = q.findValue();
                if (stateInDb == null) {
                    //TODO: handle anonymous vm
                    logger.warn(String.format("an anonymous VM[uuid:%s, state:%s] is detected on the host[uuid:%s]", cmd.hostUuid, state, cmd.hostUuid));
                    return null;
                }

                VmStateChangedOnHostMsg msg = new VmStateChangedOnHostMsg();
                msg.setVmStateAtTracingMoment(stateInDb);
                msg.setVmInstanceUuid(cmd.vmUuid);
                msg.setStateOnHost(state);
                msg.setHostUuid(cmd.hostUuid);
                bus.makeTargetServiceIdByResourceUuid(msg, VmInstanceConstant.SERVICE_ID, cmd.vmUuid);
                bus.send(msg);
                return null;
            }
        });
        return true;
    }

    @Override
    public boolean stop() {
        return true;
    }

    @Override
    public void afterHostConnected(final HostInventory host) {
        SimpleQuery<VmInstanceVO> q = dbf.createQuery(VmInstanceVO.class);
        q.select(VmInstanceVO_.uuid);
        q.add(VmInstanceVO_.state, Op.EQ, VmInstanceState.Unknown);
        q.add(VmInstanceVO_.hostUuid, Op.EQ, host.getUuid());
        final List<String> vmUuids = q.listValue();
        if (!vmUuids.isEmpty()) {
            CheckVmStateOnHypervisorMsg msg = new CheckVmStateOnHypervisorMsg();
            msg.setVmInstanceUuids(vmUuids);
            msg.setHostUuid(host.getUuid());
            bus.makeTargetServiceIdByResourceUuid(msg, HostConstant.SERVICE_ID, host.getUuid());
            bus.send(msg, new CloudBusCallBack() {
                @Override
                public void run(MessageReply reply) {
                    if (!reply.isSuccess()) {
                        //TODO
                        logger.warn(String.format("unable to check states of vms[uuids:%s] on the host[uuid:%s], %s",
                                vmUuids, host.getUuid(), reply.getError()));
                        return;
                    }

                    CheckVmStateOnHypervisorReply r = reply.castReply();
                    Map<String, VmInstanceState> states = new HashMap<String, VmInstanceState>();
                    for (Map.Entry<String, String> e : r.getStates().entrySet()) {
                        states.put(e.getKey(), VmInstanceState.valueOf(e.getValue()));
                    }

                    reportVmState(host.getUuid(), states);
                }
            });
        }
    }
}
