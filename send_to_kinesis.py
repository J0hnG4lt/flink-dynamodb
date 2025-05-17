#!/usr/bin/env python3
import boto3
import json
import time
import uuid

def main():
    # Create a Kinesis client against LocalStack
    client = boto3.client(
        'kinesis',
        region_name='us-east-1',
        aws_access_key_id='fakeMyKeyId',
        aws_secret_access_key='fakeSecretAccessKey',
        endpoint_url='http://localhost:4566'
    )

    stream_name = 'MyStream'

    # Example records
    records = [
        {'id': 'user1', 'event_timestamp': int(time.time()), 'action': 'click'},
        {'id': 'user2', 'event_timestamp': int(time.time()), 'action': 'purchase'},
        {'id': str(uuid.uuid4()), 'event_timestamp': int(time.time()), 'action': 'login'},
    ]

    for rec in records:
        data = json.dumps(rec)
        resp = client.put_record(
            StreamName=stream_name,
            Data=data,
            PartitionKey=rec['id']
        )
        print(f"Sent record {rec['id']} → shard {resp['ShardId']} seq {resp['SequenceNumber']}")

if __name__ == '__main__':
    main()
