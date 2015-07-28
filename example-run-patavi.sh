PATAVI_SERVER=$(docker run -d \
	--link postgres:postgres \
	-e PATAVI_TASK_SILENCE_TIMEOUT=20000 \
	-e PATAVI_TASK_GLOBAL_TIMEOUT=300000 \
	-e PATAVI_CACHE_DB_URL="postgresql://postgres/patavitask?user=patavitask&password=develop" \
	--name patavi_server -p 3000:3000 patavi/server)
PATAVI_WORKER=$(docker run -d --link patavi_server:patavi_server --name patavi_slow patavi/slow)
