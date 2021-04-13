package com.xunqi.gulimall.ware.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * @Description:
 * @Created: with IntelliJ IDEA.
 * @author: Zhongger
 * 2020-07-03 20:07
 **/

@Data
public class FareVo {

    private MemberAddressVo address;

    private BigDecimal fare;

}


