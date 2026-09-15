# Examples

One entry point over independent samples. Build the library first, then:

```
./mvnw -B -DskipTests package
cd examples
javac -cp ../target/tricoredb-0.1.0.jar -d out *.java
java -cp "out;../target/tricoredb-0.1.0.jar" Examples sql      # Windows
java -cp "out:../target/tricoredb-0.1.0.jar" Examples sql      # Linux/macOS
```

Samples: `basic sql nosql vector graph cache errors concurrency all`.
Connection settings come from `TRICOREDB_HOST`, `TRICOREDB_PORT`, `TRICOREDB_USER`, `TRICOREDB_PASSWORD`.
