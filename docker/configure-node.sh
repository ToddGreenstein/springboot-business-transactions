set -m

/entrypoint.sh couchbase-server &

echo "!!UNSECURE SCRIPT -- DO NOT USE IN PRODUCTION!!"

sleep 15

curl -v -X POST http://127.0.0.1:8091/pools/default -d memoryQuota=4048 -d indexMemoryQuota=2048

curl -v http://127.0.0.1:8091/node/controller/setupServices -d services=kv%2cn1ql%2Cindex

curl -v http://127.0.0.1:8091/settings/web -d port=8091 -d username=Administrator -d password=password

curl -i -u Administrator:password -X POST http://127.0.0.1:8091/settings/indexes -d 'storageMode=memory_optimized'

curl -v -u Administrator:password -X POST http://127.0.0.1:8091/pools/default/buckets -d name=default -d bucketType=couchbase -d replicaNumber=0 -d ramQuotaMB=4048 -d authType=sasl

sleep 5

fg 1
