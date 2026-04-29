import boto3

dynamodb = boto3.resource('dynamodb', region_name='eu-central-1')
table = dynamodb.Table('launchlens-doc-jurisdictions')

JURISDICTION = 'TEST_SCAN'

# 1 regulation + 1 policy, both most recently used with confirmed extractions
doc_ids = [
    '98f5f2b64cf6e48bac81d06371ae23cfd9a7f64c9a322fafb049fbbdd999c273',  # EMD2 regulation
    'a51acc10f621a29897ab5069ba2ff62558ffd283978b3ab99499a0749e086a74',  # Policy
]

for doc_id in doc_ids:
    table.put_item(Item={
        'document_id': doc_id,
        'jurisdiction': JURISDICTION
    })
    print(f"Mapped {doc_id} -> {JURISDICTION}")

print("\nDone! TEST_SCAN jurisdiction is ready.")
print("Now trigger a new scan from the UI using jurisdiction: TEST_SCAN")
