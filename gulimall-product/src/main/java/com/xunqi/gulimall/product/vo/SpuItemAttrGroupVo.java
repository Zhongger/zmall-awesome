package com.xunqi.gulimall.product.vo;

import lombok.Data;
import lombok.ToString;

import java.util.List;

/**
 * @Description:
 * @Created: with IntelliJ IDEA.
 * @author: Zhongger
 * 2020-06-19 18:18
 **/

@Data
@ToString
public class SpuItemAttrGroupVo {

    private String groupName;

    private List<Attr> attrs;

}
