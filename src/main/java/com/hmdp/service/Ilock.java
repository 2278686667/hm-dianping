package com.hmdp.service;

public interface Ilock {
    //尝试获取锁
    Boolean tryLock(Long timeSec);
    //释放锁
    void unlock();
}
