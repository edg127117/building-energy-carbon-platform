package com.platform.security;

import com.platform.audit.BackendDuty;
import com.platform.audit.BackendDutyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 运维观测从主库读取当前已提交职责；不继承调用方快照，也不改变其他业务的职责事务语义。 */
@Service
public class OperationsDutyService {
    private final BackendDutyService duties;

    public OperationsDutyService(BackendDutyService duties) {
        this.duties = duties;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, timeout = 5)
    public boolean canObserve(long userId) {
        return duties.hasDuty(userId, BackendDuty.OPERATIONS_OBSERVER);
    }
}
