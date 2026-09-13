package org.yanoproject.x.devtools.fixture;

import org.yanoproject.api.appchain.authmap.AuthenticatedMapValueValidator;
import org.yanoproject.api.appchain.authmap.AuthenticatedMapValueValidatorFactory;
import org.yanoproject.api.appchain.authmap.ValidatorInitContext;
import org.yanoproject.api.appchain.authmap.ValidatorVerdict;

/** Deliberately invalid fixture whose verdict depends on an ambient class-loader resource. */
public final class ClassLoaderSensitiveValidatorFactory
        implements AuthenticatedMapValueValidatorFactory {
    public static final String ID = "classloader-sensitive-v1";

    @Override public String id() { return ID; }
    @Override public String contractVersion() { return "authenticated-map-validator-v1"; }

    @Override
    public AuthenticatedMapValueValidator create(ValidatorInitContext context) {
        boolean ambient = getClass().getClassLoader()
                .getResource("ambient-validator-flag") != null;
        return (collection, key, value) -> ambient
                ? ValidatorVerdict.ACCEPT : ValidatorVerdict.REJECT;
    }
}
