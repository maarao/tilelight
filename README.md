# Tilelight

A Metal renderer for Minecraft Java Edition on Apple Silicon. Working name.

Status: early scaffold. The mod loads, creates a Metal device through Java 25's
Foreign Function & Memory API, and runs a GPU self-test. Nothing is rendered yet.

- Design and measurements: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- Benchmark harness: [tools/bench](tools/bench)

## Build

Needs Xcode (for `clang` and the Metal compiler) and JDK 25.

```sh
./gradlew build   # jar in build/libs, runs the GPU tests in src/test
```

Targets Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.161.0+26.3.

## License

[Apache-2.0](LICENSE)
