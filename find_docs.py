import boto3

ddb = boto3.resource('dynamodb', region_name='eu-central-1')
doc_table = ddb.Table('launchlens-documents')
resp = doc_table.scan()
items = resp.get('Items', [])

recent = [i for i in items if i.get('kind') in ('regulation', 'policy') and i.get('extraction_s3_key')]
recent.sort(key=lambda x: x.get('last_used_at', ''), reverse=True)

print('Top candidates:')
for i in recent[:10]:
    doc_id = i['id']
    kind = i['kind']
    name = i.get('display_name', '?')[:60]
    print(f"  [{kind:12}] id={doc_id}  name={name}")
