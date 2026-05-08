import pika
import json


'''
Run in shell before execution:

$ pip install pika

$ docker run -d --hostname rabbit-host \
  --name rabbitmq \
  -p 5672:5672 \
  -p 15672:15672 \
  rabbitmq:3-management
'''


EXCHANGE = "collector.results"
ROUTING_KEY = "system"  # must match groupName used in Java (or use wildcard pattern via multiple bindings)

connection = pika.BlockingConnection(
    pika.ConnectionParameters(host="localhost", port=5672)
)

channel = connection.channel()

# 1. Declare exchange (must match Java)
channel.exchange_declare(
    exchange=EXCHANGE,
    exchange_type="direct",
    durable=True
)

# 2. Create a temporary queue (auto-generated)
result = channel.queue_declare(queue="", exclusive=True)
queue_name = result.method.queue

# 3. Bind queue to exchange with routing key
channel.queue_bind(
    exchange=EXCHANGE,
    queue=queue_name,
    routing_key=ROUTING_KEY
)

print(f"[*] Waiting for messages on exchange '{EXCHANGE}' with key '{ROUTING_KEY}'")

# 4. Callback for incoming messages
def callback(ch, method, properties, body):
    print("\n=== NEW MESSAGE ===")

    message = body.decode("utf-8")
    print("Raw JSON:", message)

    try:
        data = json.loads(message)
        print("\nParsed:")
        print(json.dumps(data, indent=2))
    except Exception as e:
        print("Failed to parse JSON:", e)

# 5. Start consuming
channel.basic_consume(
    queue=queue_name,
    on_message_callback=callback,
    auto_ack=True
)

channel.start_consuming()
