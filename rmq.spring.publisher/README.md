# Batch Publish (1 Thread) vs Simple One-by-One Publish

## Performance Comparison (without waiting for ack from brocker)

| Method                 | Messages | Batch Size | Time     |
|------------------------|----------|------------|----------|
| **Batch Publish**      | 1M       | 10         | ~2 sec   |
| **One-by-One Publish** | 1M       | N/A        | ~12.5 sec|

## Observations in RabbitMQ UI

When using **batch publish**, the RabbitMQ UI displays the entire batch as **a single message**, but with an unusual structure. For example:

```json
b{"id":"4d90d564-70ea-4b72-b664-62769b568bb5","content":"Test message 0","timestamp":1747638407869}
b{"id":"5cece87b-2720-494a-b0e9-88b358899cda","content":"Test message 1","timestamp":1747638407869}
b{"id":"c22be8e4-ef90-4fce-8071-22aeeb812f41","content":"Test message 2","timestamp":1747638407869}
b{"id":"5c70dac9-bfa4-426d-98af-c24997cb45be","content":"Test message 3","timestamp":1747638407869}
b{"id":"db0e8d59-1a58-47a7-a180-6e98a8e90633","content":"Test message 4","timestamp":1747638407869}
b{"id":"3384c237-7931-4311-9d35-d5cb2829ea76","content":"Test message 5","timestamp":1747638407869}
b{"id":"996faf28-3717-41bb-b20c-7825802c1662","content":"Test message 6","timestamp":1747638407869}
b{"id":"e67d796f-abd6-4cbd-a071-5b93ca07e72f","content":"Test message 7","timestamp":1747638407869}
b{"id":"b453a9bd-a858-4a29-900f-cc6c4938da9d","content":"Test message 8","timestamp":1747638407869}
b{"id":"bb074e71-7e93-4bef-a23b-9075dc353e10","content":"Test message 9","timestamp":1747638407869}
```

## Question

> **Can consumers process this batched message?**

### Answer:

> **Yes, but the consumers should expect such message, since the format is different. So you cannot just change publisher to batch without changing consumer**




# Publish with ack

In RMQ we have an ability to wait for acknowledgement from brocker which confirms that message sent by producer is received. 

To implement it we should do few things:
- Use rmqTemplate.invoke insted of rmq.convertAndSend (which is just sending a message and forget about it)
- In the scope of invoke we call convertAndSend or other similar function from AmqpTemplate interface and then waitForConfirms or similar from RabbitOperations interface. 
Calling both in terms of same .invoke gives us an ability to publish message first and then wait for messages to be acked by brocker. 

Implementation of simple ack (publisher-confirm-type: simple):
rabbitTemplate.invoke {
    it.convertAndSend(
        RabbitMQConfig.EXCHANGE_NAME,
        RabbitMQConfig.ROUTING_KEY,
        message
    )
    it.waitForConfirmsOrDie(10_000)
}

On each message we waiting for ack from brocker. 
According to documentation Channel.waitForConfirmsOrDie waits for all messages to be ack from prev waitForConfirms call. Yes, rmq caches messages you sent in unconfirmedSet =
Collections.synchronizedSortedSet(new TreeSet<Long>()) (ChannelN implementation)

Peformance comparison:
1M messages simple ack per each message: 14m 34s
1M messages simple ack per each 10k messages, same channel used to publish all messages: 17s, almost the same to what we have for publish without waiting for ack 
