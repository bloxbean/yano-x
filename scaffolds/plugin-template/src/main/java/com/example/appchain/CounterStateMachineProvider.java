package com.example.appchain;

import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.api.appchain.AppStateMachineProvider;

/**
 * ServiceLoader provider that advertises {@link CounterStateMachine} to the
 * node. The node discovers this class through the
 * {@code META-INF/services/org.yanoproject.api.appchain.AppStateMachineProvider}
 * entry and instantiates the machine when
 * {@code yano.app-chain.state-machine=counter}.
 */
public class CounterStateMachineProvider implements AppStateMachineProvider {

    @Override
    public String id() {
        return CounterStateMachine.ID;
    }

    @Override
    public AppStateMachine create() {
        return new CounterStateMachine();
    }
}
