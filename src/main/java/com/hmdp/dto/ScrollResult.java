package com.hmdp.dto;

import lombok.Data;

import java.util.List;

/**
 * 分页查询的结果
 */
@Data
public class ScrollResult {
    private List<?> list;
    private Long minTime;
    private Integer offset;
}
