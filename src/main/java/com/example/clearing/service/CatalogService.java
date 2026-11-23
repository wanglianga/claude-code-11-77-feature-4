package com.example.clearing.service;

import com.example.clearing.domain.CatalogItem;
import com.example.clearing.dto.BillPayload;
import com.example.clearing.repo.CatalogItemRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 医保目录（本地同步副本）：目录匹配与目录版本 */
@Service
public class CatalogService {

    private final CatalogItemRepository catalogItemRepository;

    public CatalogService(CatalogItemRepository catalogItemRepository) {
        this.catalogItemRepository = catalogItemRepository;
    }

    public List<CatalogItem> list() {
        return catalogItemRepository.findAll();
    }

    @Transactional
    public CatalogItem add(String code, String name, String catalogVersion) {
        CatalogItem item = catalogItemRepository.findById(code)
                .orElse(new CatalogItem(code, name, catalogVersion));
        item.setName(name);
        item.setCatalogVersion(catalogVersion);
        item.setEnabled(true);
        return catalogItemRepository.save(item);
    }

    /** 报文中未匹配到的目录编码 */
    public List<String> missingCodes(BillPayload payload) {
        List<String> missing = new ArrayList<>();
        if (payload.getItems() == null) {
            return missing;
        }
        for (BillPayload.Item item : payload.getItems()) {
            boolean ok = item.getCatalogCode() != null
                    && catalogItemRepository.findById(item.getCatalogCode())
                            .filter(CatalogItem::isEnabled)
                            .isPresent();
            if (!ok) {
                missing.add(item.getCatalogCode() == null ? "<null>" : item.getCatalogCode());
            }
        }
        return missing;
    }

    /** 当前目录版本（所有目录项的最大版本号） */
    public String currentVersion() {
        return catalogItemRepository.findAll().stream()
                .map(CatalogItem::getCatalogVersion)
                .max(String::compareTo)
                .orElse("NONE");
    }
}
