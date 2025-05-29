# RabbitMQ Lab

This repository contains experiments and benchmarks for different RabbitMQ strategies, focusing on performance optimization and reliability.

## Project Structure

- `rmq.spring.publisher/` - Spring Boot application demonstrating different RabbitMQ publishing strategies
  - Simple one-by-one publishing
  - Batch publishing
  - Publisher confirms (acknowledgments)
  - Performance comparisons and benchmarks

## RMQ Publisher

The `rmq.spring.publisher` module demonstrates various RabbitMQ publishing strategies with performance comparisons. Key features:

- Different publishing strategies:
  - Simple one-by-one publishing
  - Batch publishing (10k messages per batch)
  - Publisher confirms with different confirmation strategies
- Performance benchmarks for each strategy
- Detailed documentation in the [rmq.spring.publisher/README.md](rmq.spring.publisher/README.md)

### Quick Start

1. Start RabbitMQ server (Docker):
```bash
docker run -d --name rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:3-management
```

2. Run the publisher application:
```bash
./gradlew :rmq.spring.publisher:bootRun
```

3. Access RabbitMQ management interface at http://localhost:15672 (guest/guest)

## Requirements

- Java 17+
- Gradle 8+
- Docker (for RabbitMQ)

## License

MIT 