#### 工作日志

2026.01.13	ver1.1 实现登录注册模块

1. 使用 Redis 保存登录验证码以及用户 token，解决 Session 共享问题；
2. 使用双层拦截器对用户 token 做校验和刷新；
3. 拦截器在每次用户请求到来时将 token 保存到 ThreadLocal，并在请求处理完成后释放之；
4. 问题遗留：目前并不能真正地向用户发送验证码，后续尝试使用邮箱或短信发送。（⚪）

2026.01.14	ver1.2.1 为商户信息查询添加了 Redis 缓存

1. DB 和缓存的双写采用 Cache Aside 策略，用 Spring 事务保证每次写入时 DB 和缓存更新的原子性；

2. 双写时先更新 DB，再删除缓存，减少不一致的可能持续时间，同时用 TTL 超时删除兜底，保证最终一致性；

3. 采用缓存空值策略防止缓存穿透，对于热点商户采用逻辑过期方案防止缓存击穿；

4. 问题遗留：

   - 如何区分热点商户和普通商户？暂未实现；（√）

   - Cache Aside 不保证双写强一致，是否有必要使用分布式锁或延迟双删？（⚪）

     > 例：某个商户在缓存中的信息到达 TTL，被 Redis 剔除，此时 thread A 更新（写）这个商户，thread B 读这个商户
     >
     > A 更新 DB ——> B 读缓存未命中，读 DB ——> A 删缓存 ——> B 将读到的 stale 数据写入缓存，**造成缓存与 DB 不一致**。

   - 不能很好地应对缓存雪崩或 Redis 服务不可用，后续尝试添加多级缓存。（⚪）

2026.01.15	ver1.2.2 添加了区分热点商户的简单策略

1. 现在可以简单区分热点商户了，对普通商户采用缓存空值避免缓存穿透，对热点商户采用逻辑过期防止缓存击穿；
2. 暂时使用评论数量区分热点商家，但实际业务应使用带有时间特征的指标，例如近期的被浏览数、被搜索数，目前没有维护这些时间相关特征。

2026.01.17	ver1.3.1 开始实现秒杀模块基本功能

1. 基于 Redis 自增生成订单的全局唯一 ID，考虑到方便统计订单数量因此没有采用 JDK 的 UUID，考虑到时钟回拨没有使用雪花算法；
2. 使用乐观锁思想解决库存超卖，利用 MySQL 行操作的原子性仅当库存有剩余时扣减，即`set ... where stock > 0`；对于更复杂的业务场景，可以使用版本号法或分布式锁保证线程安全，也可以通过水平分表 + 分段锁细化锁的粒度；
3. 目前还没有上缓存，用 JMeter 初步测试，所有请求都成功创建订单的吞吐量是每秒 350+，能够保证无超卖。

2026.01.18	ver1.3.2 实现了一人一单控制

1. 使用 Spring 事务保证扣减库存和创建订单的原子性；
2. 通过增加一次 MySQL 访问校验一人一单，利用 `select for update` 的加锁机制保证线程安全，避免幻读；

> trx：
>
> ​	SELECT count(*) ... where userId = ? and voucherId = ? FOR UPDATE;	// 校验一人一单，count(\*) == 0
>
> ​	UPDATE ... set stock = stock - 1 where voucherId = ? and stock > 0;	// 扣减库存，乐观锁避免超卖
>
> ​	INSERT into ... VALUES(......);	// 创建订单记录

3. 由于直接在 MySQL 里加锁，可以应对集群服务，支持未来对 tomcat 做水平扩展，后续做异步优化提升性能。（⚪）



#### Debug 记录

##### 2026.01.18

实现一人一单创建订单时，`synchronized`锁失效，使用 JMeter 1000 个线程同时下单，最终同一个用户下了 10 单。

```java
@Transactional
public Result seckillVouchers(...) {
	// ...
    
    return createVoucherOrder(...);   // 创建新订单
}

@Transactional
public Result createVoucherOrder(...) {
	Long userId = UserHolder.get().getId();
    synchronized (userId.toString().intern()) {
        // 扣减库存，创建订单...
        
        return ...;
    }
}
```

<img src="assets\imgs\descri_1OrderLimit_01.png"  />

<img src="assets\imgs\descri_1OrderLimit_02.png" alt="descri_1OrderLimit_02"  />

原因：**`synchronized`锁粒度与 Spring 事务粒度不匹配。**`createVoucherOrder()`内部使用 `synchronized`，在事务结束前就将其释放，等于事务还未提交其它事务就可能获得这把锁；而`createVoucherOrder()`方法的逻辑是先进行一人一单判断，再创建订单，即先快照读再当前读，那么就有可能：

> trx A：判断一人一单 ——> 创建订单 ——> （synchronized 释放，但事务未提交）——> A 提交
>
> trx B：										判断一人一单 ——> 创建订单 ——>  B 提交
>
> — — — — — — — — — — — — — — — — — — — — — — — — — — — — — — —> timeline

包括写成这样也不行，因为 `seckillVouchers()`的生命周期与 `synchronized`锁也不匹配，无法保证一人一单。

```java
@Transactional
public Result seckillVouchers(...) {
	// ...
    
    synchronized (userId.toString().intern()) {
    	return createVoucherOrder(...);   // 创建新订单
    }
}

@Transactional
public Result createVoucherOrder(...) {
	// ...
}
```

改进：本质是因为当前读与快照读交替执行，导致了线程安全问题，等于发生了**幻读**。所以在`createVoucherOrder()`事务使用 `select for update`上来就拿到行级锁，事务结束才会自动释放，这样等于对于同一个用户，事务是串行执行的。

```java
@Transactional
public Result createVoucherOrder(Long voucherId) {
    Long userId = UserHolder.get().getId();

    QueryWrapper queryWrapper = new QueryWrapper();
    queryWrapper.eq("user_id", userId);
    queryWrapper.eq("voucher_id", voucherId);
    queryWrapper.last("for update");
    
    if (baseMapper.selectCount(queryWrapper) > 0) {
        return Result.fail("当前用户已经购买过！");
    }

    // 扣减库存，创建新订单 ......
}
```

另外`seckillVouchers()`只做了一些前置判断，例如秒杀是否在活动时间内，以及是否还有库存，所以`seckillVouchers()`不需要事务保证原子性，创建订单和扣减库存都是在`createVoucherOrder()`里完成的。但`seckillVouchers()`不加事务注解，会导致事务失效（自调用），因为`seckillVouchers()`是通过 Service 层原对象调用的，而`createVoucherOrder()`要想事务生效需要通过其代理对象调用，所以还需要获取到当前对象的代理对象调用`createVoucherOrder()`方法。

```java
IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
return proxy.createVoucherOrder(voucherId);   // 创建新订单
```

另外因为直接在 MySQL 上加了行级锁，所以本地不需要 `synchronized`保证线程安全了。本身 `synchronized`也无法应对服务器集群。

使用 `select for update`保证线程安全，同一用户下单 1000 次并发测试：

![](assets\imgs\descri_1OrderLimit_03.png)

使用 `synchronized`，但这种方法不能保证集群服务下的线程安全，性能稍好些：

![](assets\imgs\descri_1OrderLimit_04.png)
