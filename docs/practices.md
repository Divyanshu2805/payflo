# Practices

[← Back to docs index](README.md)

- Maven wrapper (`mvnw` / `mvnw.cmd`) used for reproducible builds.
- Lombok annotation processing wired into both compile and test-compile Maven executions.
- `pom.xml` overrides inherited `<name>`, `<description>`, `<license>`, `<developers>`, `<scm>` from the Spring Boot parent POM to avoid unwanted inheritance.
