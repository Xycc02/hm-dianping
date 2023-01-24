-- KEYS[1] : 锁的key  ARGV[1] : 当前线程的标识

-- Reids中锁线程标识
local redisId = redis.call('get',KEYS[1])
-- 判断当前线程标识和锁中标识是否一致
if(redisId == ARGV[1]) then
    -- 释放锁
    return redis.call('del',KEYS[1])
end
return 0
