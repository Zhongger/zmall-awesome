package com.xunqi.gulimall.search.service;

import com.xunqi.common.es.SkuEsModel;

import java.io.IOException;
import java.util.List;

/**
 * @Description:
 * @Created: with IntelliJ IDEA.
 * @author: Zhongger
 * 2020-06-06 16:53
 **/
public interface ProductSaveService {

    boolean productStatusUp(List<SkuEsModel> skuEsModels) throws IOException;
}
