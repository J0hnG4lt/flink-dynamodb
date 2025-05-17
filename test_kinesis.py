import time
import boto3
from botocore.exceptions import ClientError

def wait_for_stream(stream_name: str,
                    endpoint_url: str = "http://localhost:4566",
                    region_name: str = "us-east-1",
                    access_key: str = "fakeMyKeyId",
                    secret_key: str = "fakeSecretAccessKey",
                    sleep_secs: int = 2):
    """
    Polls ListStreams until `stream_name` appears.
    Returns a boto3 Kinesis client once the stream exists.
    """
    kinesis = boto3.client(
        "kinesis",
        region_name=region_name,
        aws_access_key_id=access_key,
        aws_secret_access_key=secret_key,
        endpoint_url=endpoint_url,
    )

    print(f"⏳ Waiting for stream '{stream_name}' to be ACTIVE on {endpoint_url} …")
    while True:
        try:
            resp = kinesis.describe_stream_summary(StreamName=stream_name)
            summary = resp["StreamDescriptionSummary"]
            status = summary["StreamStatus"]
            if status == "ACTIVE":
                print(f"✅ Stream '{stream_name}' is ACTIVE")
                return kinesis
            else:
                print(f"  Found stream, but status is {status!r}; waiting…")
        except ClientError as e:
            code = e.response["Error"]["Code"]
            if code == "ResourceNotFoundException":
                print("  Stream not found yet…")
            else:
                print("  Unexpected error:", e)
                raise
        time.sleep(sleep_secs)


def print_stream_info(kinesis, stream_name: str):
    """
    Prints the shard IDs and retention period of the given stream.
    """
    desc = kinesis.describe_stream(StreamName=stream_name)["StreamDescription"]
    print(f"\nStreamDescription for '{stream_name}':")
    print(f"  RetentionPeriodHours: {desc['RetentionPeriodHours']}")
    print("  Shards:")
    for shard in desc["Shards"]:
        print(f"    - ShardId: {shard['ShardId']}")


if __name__ == "__main__":
    STREAM = "MyStream"
    client = wait_for_stream(STREAM)
    print_stream_info(client, STREAM)
