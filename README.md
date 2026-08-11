# Yano X

Yano X is the JVM extension and application ecosystem for Yano.

The build consumes published Yano artifacts and an explicit Yano JVM ZIP. It
does not invoke tasks in, copy sources from, or assume the location of a Yano
checkout.

## Local development

First publish and package the matching Yano checkout:

```bash
./gradlew publishToMavenLocal :app:yanoDistZip \
  -PleanYanoBuild=true -PskipSigning=true
```

Then pass the exact published version and ZIP to Yano X:

```bash
./gradlew verifyPublishedContracts \
  -PuseMavenLocal=true \
  -PyanoVersion=<published-yano-version> \
  -PyanoJvmDist=/absolute/path/to/yano-<build-identity>.zip
```

`mavenLocal()` is disabled unless `useMavenLocal=true`. A missing ZIP, a ZIP
whose root build identity differs from `yanoVersion`, or Maven artifacts from
another Yano version fail verification.

