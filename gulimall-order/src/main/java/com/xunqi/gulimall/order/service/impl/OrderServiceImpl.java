package com.xunqi.gulimall.order.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lly835.bestpay.model.PayResponse;
import com.lly835.bestpay.service.BestPayService;
import com.xunqi.common.exception.NoStockException;
import com.xunqi.common.to.OrderTo;
import com.xunqi.common.to.mq.SeckillOrderTo;
import com.xunqi.common.utils.PageUtils;
import com.xunqi.common.utils.Query;
import com.xunqi.common.utils.R;
import com.xunqi.common.vo.MemberResponseVo;
import com.xunqi.gulimall.order.constant.PayConstant;
import com.xunqi.gulimall.order.dao.OrderDao;
import com.xunqi.gulimall.order.entity.OrderEntity;
import com.xunqi.gulimall.order.entity.OrderItemEntity;
import com.xunqi.gulimall.order.entity.PaymentInfoEntity;
import com.xunqi.gulimall.order.enume.OrderStatusEnum;
import com.xunqi.gulimall.order.feign.CartFeignService;
import com.xunqi.gulimall.order.feign.MemberFeignService;
import com.xunqi.gulimall.order.feign.ProductFeignService;
import com.xunqi.gulimall.order.feign.WmsFeignService;
import com.xunqi.gulimall.order.interceptor.LoginUserInterceptor;
import com.xunqi.gulimall.order.service.OrderItemService;
import com.xunqi.gulimall.order.service.OrderService;
import com.xunqi.gulimall.order.service.PaymentInfoService;
import com.xunqi.gulimall.order.to.OrderCreateTo;
import com.xunqi.gulimall.order.to.SpuInfoVo;
import com.xunqi.gulimall.order.vo.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.BoundHashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.xunqi.common.constant.CartConstant.CART_PREFIX;
import static com.xunqi.gulimall.order.constant.OrderConstant.USER_ORDER_TOKEN_PREFIX;


@Slf4j
@Service("orderService")
public class OrderServiceImpl extends ServiceImpl<OrderDao, OrderEntity> implements OrderService {

    private ThreadLocal<OrderSubmitVo> confirmVoThreadLocal = new ThreadLocal<>();

    @Autowired
    private MemberFeignService memberFeignService;

    @Autowired
    private CartFeignService cartFeignService;

    @Autowired
    private WmsFeignService wmsFeignService;

    @Autowired
    private ProductFeignService productFeignService;

    @Autowired
    private OrderItemService orderItemService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private PaymentInfoService paymentInfoService;

    @Autowired
    private BestPayService bestPayService;

    @Autowired
    private ThreadPoolExecutor threadPoolExecutor;

    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        IPage<OrderEntity> page = this.page(
                new Query<OrderEntity>().getPage(params),
                new QueryWrapper<OrderEntity>()
        );

        return new PageUtils(page);
    }

    @Override
    public OrderConfirmVo confirmOrder() throws ExecutionException, InterruptedException {

        //构建OrderConfirmVo
        OrderConfirmVo confirmVo = new OrderConfirmVo();

        //获取当前用户登录的信息
        MemberResponseVo memberResponseVo = LoginUserInterceptor.loginUser.get();

        //TODO :获取当前线程请求头信息(解决Feign异步调用丢失请求头问题)
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();


        //每一个线程都来共享之前的请求数据
        RequestContextHolder.setRequestAttributes(requestAttributes);

        //1、远程查询所有的收获地址列表
        List<MemberAddressVo> address = memberFeignService.getAddress(memberResponseVo.getId());
        confirmVo.setMemberAddressVos(address);

        //开启第二个异步任务


        //每一个线程都来共享之前的请求数据
        RequestContextHolder.setRequestAttributes(requestAttributes);

        //2、远程查询购物车所有选中的购物项
        List<OrderItemVo> currentCartItems = getUserCartItems(memberResponseVo);
        confirmVo.setItems(currentCartItems);
        //feign在远程调用之前要构造请求，调用很多的拦截器

        List<OrderItemVo> items = confirmVo.getItems();
        //获取全部商品的id
        List<Long> skuIds = items.stream()
                .map((itemVo -> itemVo.getSkuId()))
                .collect(Collectors.toList());

        //远程查询商品库存信息
        R skuHasStock = wmsFeignService.getSkuHasStock(skuIds);
        List<SkuStockVo> skuStockVos = skuHasStock.getData("data", new TypeReference<List<SkuStockVo>>() {
        });

        if (skuStockVos != null && skuStockVos.size() > 0) {
            //将skuStockVos集合转换为map
            skuStockVos.stream().forEach(skuStockVo -> skuStockVo.setHasStock(true));
            Map<Long, Boolean> skuHasStockMap = skuStockVos.stream().collect(Collectors.toMap(SkuStockVo::getSkuId, SkuStockVo::getHasStock));
            confirmVo.setStocks(skuHasStockMap);
        }


        //3、查询用户积分
        Integer integration = memberResponseVo.getIntegration();
        confirmVo.setIntegration(integration);

        //4、价格数据自动计算

        //TODO 5、防重令牌(防止表单重复提交)
        //为用户设置一个token，三十分钟过期时间（存在redis）
        String token = UUID.randomUUID().toString().replace("-", "");
        redisTemplate.opsForValue().set(USER_ORDER_TOKEN_PREFIX + memberResponseVo.getId(), token, 30, TimeUnit.MINUTES);
        confirmVo.setOrderToken(token);

        return confirmVo;
    }

    /**
     * 提交订单
     *
     * @param vo
     * @param memberResponseVo
     * @return
     */
    // @Transactional(isolation = Isolation.READ_COMMITTED) 设置事务的隔离级别
    // @Transactional(propagation = Propagation.REQUIRED)   设置事务的传播级别
    // @Transactional(rollbackFor = Exception.class)
    // @GlobalTransactional(rollbackFor = Exception.class)
    @Override
    public SubmitOrderResponseVo submitOrder(OrderSubmitVo vo, MemberResponseVo memberResponseVo) {

        confirmVoThreadLocal.set(vo);

        SubmitOrderResponseVo responseVo = new SubmitOrderResponseVo();
        //去创建、下订单、验令牌、验价格、锁定库存...

        //获取当前用户登录的信息

        responseVo.setCode(0);

        //1、验证令牌是否合法【令牌的对比和删除必须保证原子性】
        String script = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
        String orderToken = vo.getOrderToken();

        //通过lure脚本原子验证令牌和删除令牌
        Long result = 1L;

        //令牌验证成功
        //1、创建订单、订单项等信息
        OrderCreateTo order = createOrder(memberResponseVo);

        //2、验证价格
        BigDecimal payAmount = order.getOrder().getPayAmount();
        vo.setPayPrice(payAmount);
        BigDecimal payPrice = vo.getPayPrice();

        if (Math.abs(payAmount.subtract(payPrice).doubleValue()) < 0.01) {
            //金额对比
            //TODO 3、保存订单
            saveOrder(order);

            //4、库存锁定,只要有异常，回滚订单数据
            //订单号、所有订单项信息(skuId,skuNum,skuName)
            WareSkuLockVo lockVo = new WareSkuLockVo();
            lockVo.setOrderSn(order.getOrder().getOrderSn());

            //获取出要锁定的商品数据信息
            List<OrderItemVo> orderItemVos = order.getOrderItems().stream().map((item) -> {
                OrderItemVo orderItemVo = new OrderItemVo();
                orderItemVo.setSkuId(item.getSkuId());
                orderItemVo.setCount(item.getSkuQuantity());
                orderItemVo.setTitle(item.getSkuName());
                return orderItemVo;
            }).collect(Collectors.toList());
            lockVo.setLocks(orderItemVos);

            //TODO 调用远程锁定库存的方法
            //出现的问题：扣减库存成功了，但是由于网络原因超时，出现异常，导致订单事务回滚，库存事务不回滚(解决方案：seata)
            //为了保证高并发，不推荐使用seata，因为是加锁，并行化，提升不了效率,可以发消息给库存服务
            R r = wmsFeignService.orderLockStock(lockVo);
            if (r.getCode() == 0) {
                //锁定成功
                responseVo.setOrder(order.getOrder());
                // int i = 10/0;

                //TODO 订单创建成功，发送消息给MQ
                rabbitTemplate.convertAndSend("order-event-exchange", "order.create.order", order.getOrder());

                //删除购物车里的数据
                redisTemplate.delete(CART_PREFIX + memberResponseVo.getId());
                return responseVo;
            } else {
                //锁定失败
                String msg = (String) r.get("msg");
                throw new NoStockException(msg);
                // responseVo.setCode(3);
                // return responseVo;
            }

        } else {
            responseVo.setCode(2);
            return responseVo;
        }

    }

    /**
     * 按照订单号获取订单信息
     *
     * @param orderSn
     * @return
     */
    @Override
    public OrderEntity getOrderByOrderSn(String orderSn) {

        OrderEntity orderEntity = this.baseMapper.selectOne(new QueryWrapper<OrderEntity>().eq("order_sn", orderSn));

        return orderEntity;
    }

    /**
     * 关闭订单
     *
     * @param orderEntity
     */
    @Override
    public void closeOrder(OrderEntity orderEntity) {

        //关闭订单之前先查询一下数据库，判断此订单状态是否已支付
        OrderEntity orderInfo = this.getOne(new QueryWrapper<OrderEntity>().
                eq("order_sn", orderEntity.getOrderSn()));

        if (orderInfo.getStatus().equals(OrderStatusEnum.CREATE_NEW.getCode())) {
            //代付款状态进行关单
            OrderEntity orderUpdate = new OrderEntity();
            orderUpdate.setId(orderInfo.getId());
            orderUpdate.setStatus(OrderStatusEnum.CANCLED.getCode());
            this.updateById(orderUpdate);

            // 发送消息给MQ
            OrderTo orderTo = new OrderTo();
            BeanUtils.copyProperties(orderInfo, orderTo);

            try {
                //TODO 确保每个消息发送成功，给每个消息做好日志记录，(给数据库保存每一个详细信息)保存每个消息的详细信息
                rabbitTemplate.convertAndSend("order-event-exchange", "order.release.other", orderTo);
            } catch (Exception e) {
                //TODO 定期扫描数据库，重新发送失败的消息
            }
        }
    }


    /**
     * 获取当前订单的支付信息
     *
     * @param orderSn
     * @return
     */
    @Override
    public PayVo getOrderPay(String orderSn) {

        PayVo payVo = new PayVo();
        OrderEntity orderInfo = this.getOrderByOrderSn(orderSn);

        //保留两位小数点，向上取值
        BigDecimal payAmount = orderInfo.getPayAmount().setScale(2, BigDecimal.ROUND_UP);
        payVo.setTotal_amount(payAmount.toString());
        payVo.setOut_trade_no(orderInfo.getOrderSn());

        //查询订单项的数据
        List<OrderItemEntity> orderItemInfo = orderItemService.list(
                new QueryWrapper<OrderItemEntity>().eq("order_sn", orderSn));
        OrderItemEntity orderItemEntity = orderItemInfo.get(0);
        payVo.setBody(orderItemEntity.getSkuAttrsVals());

        payVo.setSubject(orderItemEntity.getSkuName());

        return payVo;
    }

    /**
     * 查询当前用户所有订单数据
     *
     * @param params
     * @return
     */
    @Override
    public PageUtils queryPageWithItem(Map<String, Object> params) {

        MemberResponseVo memberResponseVo = LoginUserInterceptor.loginUser.get();

        IPage<OrderEntity> page = this.page(
                new Query<OrderEntity>().getPage(params),
                new QueryWrapper<OrderEntity>()
                        .eq("member_id", memberResponseVo.getId()).orderByDesc("create_time")
        );

        //遍历所有订单集合
        List<OrderEntity> orderEntityList = page.getRecords().stream().map(order -> {
            //根据订单号查询订单项里的数据
            List<OrderItemEntity> orderItemEntities = orderItemService.list(new QueryWrapper<OrderItemEntity>()
                    .eq("order_sn", order.getOrderSn()));
            order.setOrderItemEntityList(orderItemEntities);
            return order;
        }).collect(Collectors.toList());

        page.setRecords(orderEntityList);

        return new PageUtils(page);
    }

    /**
     * 保存订单所有数据
     *
     * @param orderCreateTo
     */
    private void saveOrder(OrderCreateTo orderCreateTo) {

        //获取订单信息
        OrderEntity order = orderCreateTo.getOrder();
        order.setModifyTime(new Date());
        order.setCreateTime(new Date());
        //保存订单
        this.baseMapper.insert(order);

        //获取订单项信息
        List<OrderItemEntity> orderItems = orderCreateTo.getOrderItems();
        //批量保存订单项数据
        orderItemService.saveBatch(orderItems);
    }


    private OrderCreateTo createOrder(MemberResponseVo memberResponseVo) {

        OrderCreateTo createTo = new OrderCreateTo();

        //1、生成订单号
        String orderSn = IdWorker.getTimeId();
        OrderEntity orderEntity = builderOrder(orderSn);

        //2、获取到所有的订单项
        List<OrderItemEntity> orderItemEntities = builderOrderItems(orderSn, memberResponseVo);

        //3、验价(计算价格、积分等信息)
        computePrice(orderEntity, orderItemEntities);

        createTo.setOrder(orderEntity);
        createTo.setOrderItems(orderItemEntities);

        return createTo;
    }

    /**
     * 计算价格的方法
     *
     * @param orderEntity
     * @param orderItemEntities
     */
    private void computePrice(OrderEntity orderEntity, List<OrderItemEntity> orderItemEntities) {

        //总价
        BigDecimal total = new BigDecimal("0.0");
        //优惠价
        BigDecimal coupon = new BigDecimal("0.0");
        BigDecimal intergration = new BigDecimal("0.0");
        BigDecimal promotion = new BigDecimal("0.0");
        BigDecimal freightAmount = new BigDecimal("0.0");

        //积分、成长值
        Integer integrationTotal = 0;
        Integer growthTotal = 0;

        //订单总额，叠加每一个订单项的总额信息
        for (OrderItemEntity orderItem : orderItemEntities) {
            //优惠价格信息
            orderItem.setCouponAmount(coupon);
            orderItem.setPromotionAmount(promotion);
            orderItem.setIntegrationAmount(intergration);

            coupon = coupon.add(orderItem.getCouponAmount());
            promotion = promotion.add(orderItem.getPromotionAmount());
            intergration = intergration.add(orderItem.getIntegrationAmount());

            //总价
            total = total.add(orderItem.getRealAmount());

            //积分信息和成长值信息
            orderItem.setGiftIntegration(integrationTotal);
            integrationTotal += orderItem.getGiftIntegration();
            orderItem.setGiftGrowth(growthTotal);
            growthTotal += orderItem.getGiftGrowth();

        }
        //1、订单价格相关的
        orderEntity.setTotalAmount(total);
        //设置应付总额(总额+运费)
        orderEntity.setPayAmount(total.add(freightAmount));
        orderEntity.setCouponAmount(coupon);
        orderEntity.setPromotionAmount(promotion);
        orderEntity.setIntegrationAmount(intergration);

        //设置积分成长值信息
        orderEntity.setIntegration(integrationTotal);
        orderEntity.setGrowth(growthTotal);

        //设置删除状态(0-未删除，1-已删除)
        orderEntity.setDeleteStatus(0);

    }


    /**
     * 构建订单数据
     *
     * @param orderSn
     * @return
     */
    private OrderEntity builderOrder(String orderSn) {

        //获取当前用户登录信息
        MemberResponseVo memberResponseVo = LoginUserInterceptor.loginUser.get();

        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setMemberId(memberResponseVo.getId());
        orderEntity.setOrderSn(orderSn);
        orderEntity.setMemberUsername(memberResponseVo.getUsername());

        OrderSubmitVo orderSubmitVo = confirmVoThreadLocal.get();

//        //远程获取收货地址和运费信息
//        R fareAddressVo = wmsFeignService.getFare(orderSubmitVo.getAddrId());
//        FareVo fareResp = fareAddressVo.getData("data", new TypeReference<FareVo>() {
//        });
//
//        //获取到运费信息
//        BigDecimal fare = fareResp.getFare();
//        orderEntity.setFreightAmount(fare);
//
//        //获取到收货地址信息
//        MemberAddressVo address = fareResp.getAddress();
//        //设置收货人信息
//        orderEntity.setReceiverName(address.getName());
//        orderEntity.setReceiverPhone(address.getPhone());
//        orderEntity.setReceiverPostCode(address.getPostCode());
//        orderEntity.setReceiverProvince(address.getProvince());
//        orderEntity.setReceiverCity(address.getCity());
//        orderEntity.setReceiverRegion(address.getRegion());
//        orderEntity.setReceiverDetailAddress(address.getDetailAddress());

        //设置订单相关的状态信息
        orderEntity.setStatus(OrderStatusEnum.CREATE_NEW.getCode());
        orderEntity.setAutoConfirmDay(7);
        orderEntity.setConfirmStatus(0);

        return orderEntity;
    }

    /**
     * 构建所有订单项数据
     *
     * @return
     */
    public List<OrderItemEntity> builderOrderItems(String orderSn, MemberResponseVo memberResponseVo) {

        List<OrderItemEntity> orderItemEntityList = new ArrayList<>();

        List<OrderItemVo> currentCartItems = getUserCartItems(memberResponseVo);
        if (currentCartItems != null && currentCartItems.size() > 0) {
//            orderItemEntityList = currentCartItems.stream().map((items) -> {
//                //构建订单项数据
//                OrderItemEntity orderItemEntity = builderOrderItem(items);
//                orderItemEntity.setOrderSn(orderSn);
//
//                return orderItemEntity;
//            }).collect(Collectors.toList());

            currentCartItems.forEach(orderItemVo -> {
                OrderItemEntity orderItemEntity = new OrderItemEntity();
                BeanUtils.copyProperties(orderItemVo, orderItemEntity);
                orderItemEntity.setSkuId(orderItemVo.getSkuId());
                orderItemEntity.setSkuPrice(orderItemVo.getPrice());
                orderItemEntity.setOrderSn(orderSn);
                orderItemEntity.setRealAmount(orderItemVo.getPrice());
                orderItemEntityList.add(orderItemEntity);
            });
        }

        return orderItemEntityList;
    }

    /**
     * 构建某一个订单项的数据
     *
     * @param items
     * @return
     */
    private OrderItemEntity builderOrderItem(OrderItemVo items) {

        OrderItemEntity orderItemEntity = new OrderItemEntity();

        //1、商品的spu信息
        Long skuId = items.getSkuId();
        //获取spu的信息
        R spuInfo = productFeignService.getSpuInfoBySkuId(skuId);
        SpuInfoVo spuInfoData = spuInfo.getData("data", new TypeReference<SpuInfoVo>() {
        });
        orderItemEntity.setSpuId(spuInfoData.getId());
        orderItemEntity.setSpuName(spuInfoData.getSpuName());
        orderItemEntity.setSpuBrand(spuInfoData.getBrandName());
        orderItemEntity.setCategoryId(spuInfoData.getCatalogId());

        //2、商品的sku信息
        orderItemEntity.setSkuId(skuId);
        orderItemEntity.setSkuName(items.getTitle());
        orderItemEntity.setSkuPic(items.getImage());
        orderItemEntity.setSkuPrice(items.getPrice());
        orderItemEntity.setSkuQuantity(items.getCount());

        //使用StringUtils.collectionToDelimitedString将list集合转换为String
        String skuAttrValues = StringUtils.collectionToDelimitedString(items.getSkuAttrValues(), ";");
        orderItemEntity.setSkuAttrsVals(skuAttrValues);

        //3、商品的优惠信息

        //4、商品的积分信息
        orderItemEntity.setGiftGrowth(items.getPrice().multiply(new BigDecimal(items.getCount())).intValue());
        orderItemEntity.setGiftIntegration(items.getPrice().multiply(new BigDecimal(items.getCount())).intValue());

        //5、订单项的价格信息
        orderItemEntity.setPromotionAmount(BigDecimal.ZERO);
        orderItemEntity.setCouponAmount(BigDecimal.ZERO);
        orderItemEntity.setIntegrationAmount(BigDecimal.ZERO);

        //当前订单项的实际金额.总额 - 各种优惠价格
        //原来的价格
        BigDecimal origin = orderItemEntity.getSkuPrice().multiply(new BigDecimal(orderItemEntity.getSkuQuantity().toString()));
        //原价减去优惠价得到最终的价格
        BigDecimal subtract = origin.subtract(orderItemEntity.getCouponAmount())
                .subtract(orderItemEntity.getPromotionAmount())
                .subtract(orderItemEntity.getIntegrationAmount());
        orderItemEntity.setRealAmount(subtract);

        return orderItemEntity;
    }


    /**
     * 处理支付宝的支付结果
     *
     * @param asyncVo
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public String handlePayResult(PayAsyncVo asyncVo) {

        //保存交易流水信息
        PaymentInfoEntity paymentInfo = new PaymentInfoEntity();
        paymentInfo.setOrderSn(asyncVo.getOut_trade_no());
        paymentInfo.setAlipayTradeNo(asyncVo.getTrade_no());
        paymentInfo.setTotalAmount(new BigDecimal(asyncVo.getBuyer_pay_amount()));
        paymentInfo.setSubject(asyncVo.getBody());
        paymentInfo.setPaymentStatus(asyncVo.getTrade_status());
        paymentInfo.setCreateTime(new Date());
        paymentInfo.setCallbackTime(asyncVo.getNotify_time());
        //添加到数据库中
        this.paymentInfoService.save(paymentInfo);

        //修改订单状态
        //获取当前状态
        String tradeStatus = asyncVo.getTrade_status();

        if (tradeStatus.equals("TRADE_SUCCESS") || tradeStatus.equals("TRADE_FINISHED")) {
            //支付成功状态
            String orderSn = asyncVo.getOut_trade_no(); //获取订单号
            this.updateOrderStatus(orderSn, OrderStatusEnum.PAYED.getCode(), PayConstant.ALIPAY);
        }

        return "success";
    }


    /**
     * 修改订单状态
     *
     * @param orderSn
     * @param code
     */
    private void updateOrderStatus(String orderSn, Integer code, Integer payType) {

        this.baseMapper.updateOrderStatus(orderSn, code, payType);
    }

    /**
     * 微信异步通知结果
     *
     * @param notifyData
     * @return
     */
    @Override
    public String asyncNotify(String notifyData) {

        //签名效验
        PayResponse payResponse = bestPayService.asyncNotify(notifyData);
        log.info("payResponse={}", payResponse);

        //2.金额效验（从数据库查订单）
        OrderEntity orderEntity = this.getOrderByOrderSn(payResponse.getOrderId());

        //如果查询出来的数据是null的话
        //比较严重(正常情况下是不会发生的)发出告警：钉钉、短信
        if (orderEntity == null) {
            //TODO 发出告警，钉钉，短信
            throw new RuntimeException("通过订单编号查询出来的结果是null");
        }

        //判断订单状态状态是否为已支付或者是已取消,如果不是订单状态不是已支付状态
        Integer status = orderEntity.getStatus();
        if (status.equals(OrderStatusEnum.PAYED.getCode()) || status.equals(OrderStatusEnum.CANCLED.getCode())) {
            throw new RuntimeException("该订单已失效,orderNo=" + payResponse.getOrderId());
        }

        /*//判断金额是否一致,Double类型比较大小，精度问题不好控制
        if (orderEntity.getPayAmount().compareTo(BigDecimal.valueOf(payResponse.getOrderAmount())) != 0) {
            //TODO 告警
            throw new RuntimeException("异步通知中的金额和数据库里的不一致,orderNo=" + payResponse.getOrderId());
        }*/

        //3.修改订单支付状态
        //支付成功状态
        String orderSn = orderEntity.getOrderSn();
        this.updateOrderStatus(orderSn, OrderStatusEnum.PAYED.getCode(), PayConstant.WXPAY);

        //4.告诉微信不要再重复通知了
        return "<xml>\n" +
                "  <return_code><![CDATA[SUCCESS]]></return_code>\n" +
                "  <return_msg><![CDATA[OK]]></return_msg>\n" +
                "</xml>";
    }


    /**
     * 创建秒杀单
     *
     * @param orderTo
     */
    @Override
    public void createSeckillOrder(SeckillOrderTo orderTo) {

        //TODO 保存订单信息
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setOrderSn(orderTo.getOrderSn());
        orderEntity.setMemberId(orderTo.getMemberId());
        orderEntity.setCreateTime(new Date());
        BigDecimal totalPrice = orderTo.getSeckillPrice().multiply(BigDecimal.valueOf(orderTo.getNum()));
        orderEntity.setPayAmount(totalPrice);
        orderEntity.setStatus(OrderStatusEnum.CREATE_NEW.getCode());

        //保存订单
        this.save(orderEntity);

        //保存订单项信息
        OrderItemEntity orderItem = new OrderItemEntity();
        orderItem.setOrderSn(orderTo.getOrderSn());
        orderItem.setRealAmount(totalPrice);

        orderItem.setSkuQuantity(orderTo.getNum());

        //保存商品的spu信息
        R spuInfo = productFeignService.getSpuInfoBySkuId(orderTo.getSkuId());
        SpuInfoVo spuInfoData = spuInfo.getData("data", new TypeReference<SpuInfoVo>() {
        });
        orderItem.setSpuId(spuInfoData.getId());
        orderItem.setSpuName(spuInfoData.getSpuName());
        orderItem.setSpuBrand(spuInfoData.getBrandName());
        orderItem.setCategoryId(spuInfoData.getCatalogId());

        //保存订单项数据
        orderItemService.save(orderItem);
    }


    public static void main(String[] args) {
        String orderSn = IdWorker.getTimeId().substring(0, 16);
        System.out.println(orderSn);
    }

    /**
     * 获取购物车里面的数据
     *
     * @param cartKey
     * @return
     */
    private List<CartItemVo> getCartItems(String cartKey) {
        //获取购物车里面的所有商品
        BoundHashOperations<String, Object, Object> operations = redisTemplate.boundHashOps(cartKey);
        List<Object> values = operations.values();
        if (values != null && values.size() > 0) {
            List<CartItemVo> cartItemVoStream = values.stream().map((obj) -> {
                String str = (String) obj;
                CartItemVo cartItem = JSON.parseObject(str, CartItemVo.class);
                return cartItem;
            }).collect(Collectors.toList());
            return cartItemVoStream;
        }
        return null;

    }

    private List<OrderItemVo> getUserCartItems(MemberResponseVo memberResponseVo) {

        List<OrderItemVo> list = new ArrayList<>();
        List<CartItemVo> cartItemVoList = new ArrayList<>();
        //获取当前用户登录的信息
        MemberResponseVo userInfoTo = memberResponseVo;
        //如果用户未登录直接返回null
        if (userInfoTo.getId() == null) {
            return null;
        } else {
            //获取购物车项
            String cartKey = CART_PREFIX + userInfoTo.getId();
            //获取所有的
            List<CartItemVo> cartItems = getCartItems(cartKey);
            if (cartItems == null) {
                return null;
            }
            cartItems.forEach(
                    cartItemVo -> {
                        OrderItemVo orderItemVo = new OrderItemVo();
                        orderItemVo.setCheck(cartItemVo.getCheck());
                        orderItemVo.setImage(cartItemVo.getImage());
                        orderItemVo.setPrice(cartItemVo.getPrice());
                        orderItemVo.setSkuAttrValues(cartItemVo.getSkuAttrValues());
                        orderItemVo.setCount(cartItemVo.getCount());
                        orderItemVo.setTotalPrice(cartItemVo.getTotalPrice());
                        orderItemVo.setTitle(cartItemVo.getTitle());
                        orderItemVo.setSkuId(cartItemVo.getSkuId());
                        list.add(orderItemVo);
                    }
            );
//            //筛选出选中的
//            cartItemVoList = cartItems.stream()
//                    .filter(items -> items.getCheck())
//                    .map(item -> {
//                        //更新为最新的价格（查询数据库）
//                        BigDecimal price = productFeignService.getPrice(item.getSkuId());
//                        item.setPrice(price);
//                        return item;
//                    })
//                    .collect(Collectors.toList());
        }

        return list;
    }


}

/**
 * @Description: 购物项内容
 * @Created: with IntelliJ IDEA.
 * @author: Zhongger
 * 2020-06-30 16:43
 **/

class CartItemVo {

    private Long skuId;

    private Boolean check = true;

    private String title;

    private String image;

    /**
     * 商品套餐属性
     */
    private List<String> skuAttrValues;

    private BigDecimal price;

    private Integer count;

    private BigDecimal totalPrice;

    public Long getSkuId() {
        return skuId;
    }

    public void setSkuId(Long skuId) {
        this.skuId = skuId;
    }

    public Boolean getCheck() {
        return check;
    }

    public void setCheck(Boolean check) {
        this.check = check;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public List<String> getSkuAttrValues() {
        return skuAttrValues;
    }

    public void setSkuAttrValues(List<String> skuAttrValues) {
        this.skuAttrValues = skuAttrValues;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public Integer getCount() {
        return count;
    }

    public void setCount(Integer count) {
        this.count = count;
    }

    /**
     * 计算当前购物项总价
     *
     * @return
     */
    public BigDecimal getTotalPrice() {

        return this.price.multiply(new BigDecimal("" + this.count));
    }

    public void setTotalPrice(BigDecimal totalPrice) {
        this.totalPrice = totalPrice;
    }


}
