/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.vizury.videocache.core;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.handler.codec.http.DefaultHttpRequest;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;

import com.twitter.finagle.Service;
import com.twitter.util.Future;
import com.twitter.util.FutureEventListener;
import com.vizury.videocache.common.DBConnecter;
import com.vizury.videocache.product.ProductDetail;
import com.vizury.videocache.product.ProductFileReader;
import com.vizury.videocache.product.ProductReader;

/**
 *
 * @author sankalpkulshrestha
 */
class CampaignExecutor implements Runnable {

    private final String campaignId;
    private final HashMap<String, String> propertyMap;
    private final JedisPool jedisPool;
    private Service<HttpRequest, HttpResponse> client;
    private Integer threadId;

    public CampaignExecutor(String campaignId, HashMap<String, String> propertyMap, 
            JedisPool jedisPool, Service<HttpRequest, HttpResponse> client, Integer threadId) {
        this.campaignId = campaignId;
        this.propertyMap = propertyMap;
        this.jedisPool = jedisPool;
        this.client = client;
        this.threadId = threadId;
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
                            generateVideo(product);
                        }
                    }
                }
            }
        }
    } // End of run()

    
    @SuppressWarnings("unchecked")
    private void generateVideo(ProductDetail product) {
        JSONObject jReq = new JSONObject();
        jReq.put("type", "command");
        String jobID = threadId.toString() + "." + UUID.randomUUID().toString();
        jReq.put("job_id", jobID);
        jReq.put("landing_page_url", product.getLandingPageUrl());
        jReq.put("video_url", propertyMap.get("campaignProductListLocation"));
        jReq.put("campaign_id", campaignId);
        JSONArray products = new JSONArray();
        // Add main product
        JSONObject productInfo = new JSONObject();
        productInfo.put("img_url", product.getCdnUrl());
        productInfo.put("pname", product.getProductName());
        productInfo.put("pid", product.getProductId());
        products.add(productInfo);
        // Add recommended products
        for (ProductDetail recProduct : product.getRecommendedProduct()) {
            JSONObject recProductInfo = new JSONObject();
            recProductInfo.put("img_url", recProduct.getCdnUrl());
            recProductInfo.put("pname", recProduct.getProductName());
            recProductInfo.put("pid", recProduct.getProductId());
            products.add(recProductInfo);
        }
        jReq.put("products", products);
        
        HttpRequest request = createJsonRequest(jReq.toJSONString());
        Future<HttpResponse> slaveAckF = client.apply(request);

        slaveAckF.addEventListener(new FutureEventListener<HttpResponse>() {
            public void onFailure(Throwable e) {
                System.out.println(e.getCause() + " : " + e.getMessage());
            }

            public void onSuccess(HttpResponse response) {
                System.out.println("[GeneratorMaster] Job ack received for job");
            }
        });
    }
    

    private HttpRequest createJsonRequest(String content) {
        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/genvid/");
        ChannelBuffer buffer = ChannelBuffers.copiedBuffer(content, UTF_8);
        request.setContent(buffer);
        request.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/json; charset=UTF-8");
        request.setHeader(HttpHeaders.Names.CONTENT_LENGTH, buffer.readableBytes());
        return request;
    }
    
}
