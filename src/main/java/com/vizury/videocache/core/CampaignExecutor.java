/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.vizury.videocache.core;

import com.vizury.videocache.common.DBConnecter;
import com.vizury.videocache.product.ProductDetail;
import com.vizury.videocache.product.ProductReader;
import java.util.List;
import com.vizury.videocache.product.ProductFileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.json.simple.JSONArray;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;

/**
 *
 * @author sankalpkulshrestha
 */
class CampaignExecutor implements Runnable {

    private final String campaignId;
    private final HashMap<String, String> propertyMap;
    private final JedisPool jedisPool;

    public CampaignExecutor(String campaignId, HashMap<String, String> propertyMap, JedisPool jedisPool) {
        this.campaignId = campaignId;
        this.propertyMap = propertyMap;
        this.jedisPool = jedisPool;
    }

    @Override
    public void run() {
        ProductReader productReader = new ProductFileReader(propertyMap.get("campaignProductListLocation"));
        List<ProductDetail> productDetailList = productReader.getProductDetailList(campaignId);
        List<ProductDetail> notFoundProductList = new ArrayList();
        //Checking in Redis. This will return a list of ProductDetail which are not found
        if (!productDetailList.isEmpty()) {
            int index = 0;
            int subIndex = 0;
            int redisBatchSize = Integer.parseInt(propertyMap.get("redisBatchSize"));
            List<ProductDetail> keyList = new ArrayList();
            Jedis jedisConnection = jedisPool.getResource();
            Iterator iterator = productDetailList.iterator();
            ProductDetail productDetail = null;
            while (iterator.hasNext()) {
                productDetail = (ProductDetail) iterator.next();
                if (index < redisBatchSize - 1) {
                    keyList.add(productDetail);
                } else {
                    keyList.add(productDetail);
                    Pipeline pipeline = jedisConnection.pipelined();
                    Response<String>[] response = new Response[redisBatchSize];
                    subIndex = 0;
                    for (ProductDetail key : keyList) {
                        response[subIndex] = pipeline.hget(Integer.toString(key.getSegmentId()), key.getBannerClassId() + "_" + key.getProductId());
                        subIndex++;
                    }
                    pipeline.sync();
                    subIndex = 0;
                    for (ProductDetail key : keyList) {
                        if (response[subIndex].get() == null) {
                            notFoundProductList.add(key);
                        }
                        subIndex++;
                    }
                    keyList.clear();
                    index = -1;
                }
                index++;
            }
            if (index > 0) {
                Pipeline pipeline = jedisConnection.pipelined();
                Response<String>[] response = new Response[redisBatchSize];
                subIndex = 0;
                for (ProductDetail key : keyList) {
                    response[subIndex] = pipeline.hget(Integer.toString(key.getSegmentId()), key.getBannerClassId() + "_" + key.getProductId());
                    subIndex++;
                }
                pipeline.sync();
                subIndex = 0;
                for (ProductDetail key : keyList) {
                    if (response[subIndex].get() == null) {
                        notFoundProductList.add(key);
                    }
                    subIndex++;
                }
            }
            jedisPool.returnResource(jedisConnection);
        }
        //Going ahead with NotFoundList
        if (!notFoundProductList.isEmpty()) {
            DBConnecter dbConnecter = new DBConnecter(propertyMap.get("dbhost"), propertyMap.get("dbuser"), propertyMap.get("dbpassword"));
            String namespace = dbConnecter.getNamespace(propertyMap, campaignId);
            dbConnecter.disconnect();
            CacheConnect cache = new MemcacheConnect(propertyMap.get("memcacheIp"), Integer.parseInt(propertyMap.get("memcachePort")));
            cache.setTimeout(Integer.parseInt(propertyMap.get("memcacheTimeout")));
            if (namespace != null) {
                //Making bulk call to memcache
                for (ProductDetail product : notFoundProductList) {
                    product.setNamespace(namespace);
                }
                Map<String, Object> productDetailMap = cache.getBulk(notFoundProductList, "_1_");
                if (productDetailMap != null) {
                    HashMap<String, String> categoryMap = new HashMap<>();
                    HashMap<String, String> subCategoryMap = new HashMap<>();
                    HashMap<String, String> subSubCategoryMap = new HashMap<>();
                    HashMap<String, ProductDetail> recommendedProductDetail = new HashMap<>();
                    for (ProductDetail product : notFoundProductList) {
                        product.jsonToProductDetail((String) productDetailMap.get(product.getNamespace() + "_1_" + product.getProductId()));
                        product.getRecommendedProducts(cache, subSubCategoryMap, subCategoryMap, categoryMap, recommendedProductDetail, Integer.parseInt(propertyMap.get("numberOfRecommendations")));
                        if (product.isValidProduct()) {
                            System.out.println(product.toString());
                            //Send to slave
                        }
                    }
                }
            }
        }
    }
}
