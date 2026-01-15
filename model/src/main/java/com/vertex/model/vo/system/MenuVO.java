package com.vertex.model.vo.system;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 菜单VO
 */
@Data
public class MenuVO {

    private Long id;
    private Long parentId;
    private String name;
    private String i18nKey;
    private String path;
    private String component;
    private String icon;
    private Integer type;
    private String permission;
    private Integer sort;
    private Integer status;
    private LocalDateTime createTime;
    
    /** 子菜单列表 */
    private List<MenuVO> children;
}
