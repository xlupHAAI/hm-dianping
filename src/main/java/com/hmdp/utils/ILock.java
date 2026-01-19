package com.hmdp.utils;

public interface ILock {

    boolean tryLock(long ttl);

    void unlock();
}
