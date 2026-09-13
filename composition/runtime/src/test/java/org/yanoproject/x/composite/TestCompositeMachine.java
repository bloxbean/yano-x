package org.yanoproject.x.composite;

import org.yanoproject.api.appchain.AppStateMachine;

/** Test fixture pairing a profile descriptor with an ordinary state machine. */
interface TestCompositeMachine extends AppStateMachine {
    ComponentDescriptor descriptor();

    @Override
    default String id() {
        return descriptor().componentId();
    }
}
