package org.yanoproject.app.e2e;

import io.quarkus.test.junit.QuarkusTestProfile;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Runs the annotated end-to-end class against one devnet node started from the packaged Yano X JVM
 * distribution, configured by the profile's overrides. Yano X never boots the Yano host in-process.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(PackagedDevnetNode.class)
@interface PackagedDevnet {
    /** Supplies the node configuration, exactly as the in-process profile would. */
    Class<? extends QuarkusTestProfile> profile();

    /** Bundle JAR name prefixes moved out of {@code plugins/}. */
    String[] removeBundles() default {};

    /** Bundle JAR name prefixes copied from {@code optional-plugins/} into {@code plugins/}. */
    String[] addOptionalBundles() default {};
}
