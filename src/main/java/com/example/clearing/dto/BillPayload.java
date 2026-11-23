package com.example.clearing.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 结算清单报文（登记时作为 payload 传入，内容哈希按其原文计算） */
public class BillPayload {

    private List<Item> items = new ArrayList<>();

    /** 演示用故障注入：强制触发待遇规则冲突 */
    private boolean forceRuleConflict;

    public List<Item> getItems() {
        return items;
    }

    public void setItems(List<Item> items) {
        this.items = items;
    }

    public boolean isForceRuleConflict() {
        return forceRuleConflict;
    }

    public void setForceRuleConflict(boolean forceRuleConflict) {
        this.forceRuleConflict = forceRuleConflict;
    }

    public static class Item {
        private String catalogCode;
        private int qty;
        private BigDecimal price;

        public Item() {
        }

        public Item(String catalogCode, int qty, BigDecimal price) {
            this.catalogCode = catalogCode;
            this.qty = qty;
            this.price = price;
        }

        public String getCatalogCode() {
            return catalogCode;
        }

        public void setCatalogCode(String catalogCode) {
            this.catalogCode = catalogCode;
        }

        public int getQty() {
            return qty;
        }

        public void setQty(int qty) {
            this.qty = qty;
        }

        public BigDecimal getPrice() {
            return price;
        }

        public void setPrice(BigDecimal price) {
            this.price = price;
        }
    }
}
