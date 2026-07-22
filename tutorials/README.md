# Learning Tutorials

A three-part tutorial series that recaps the stack this project is built on:
**Java 21**, **Spring Boot 3**, and **JPA / Hibernate**. Each PDF is
self-contained and uses real examples drawn from the `event-ticketing`
codebase.

| # | Tutorial | Topics |
|---|----------|--------|
| 01 | [Java Recap](01-java-oop-exceptions-streams.pdf) | Objects & classes, encapsulation, inheritance, polymorphism, abstraction, exceptions, the Streams API |
| 02 | [Spring Boot Recap](02-spring-boot-rest.pdf) | IoC/DI, creating an app, core stereotypes, dependency injection, configuration, building a REST API |
| 03 | [JPA & Hibernate](03-jpa-hibernate-spring-boot.pdf) | JPA vs Hibernate vs Spring Data, entity mapping, relationships, repositories, transactions, locking |

Read them in order — each builds on the previous one.

## Regenerating the PDFs

The PDFs are produced from HTML with Chromium's print-to-PDF. Sources live in
the scratchpad generator (`gen.py`, `hl.py`, `content_*.py`); run `gen.py` to
rebuild the HTML, then:

```bash
chrome --headless --no-pdf-header-footer --print-to-pdf=out.pdf in.html
```
