package org.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.example.domain.Stock;

@Mapper
public interface StockMapper extends BaseMapper<Stock> {

    /**
     * 库存扣减（乐观锁版本）
     * 使用version字段防止并发修改和ABA问题
     *
     * @param skuId SKU ID
     * @param quantity 扣减数量
     * @param version 当前版本号
     * @return 更新行数（0表示更新失败，1表示更新成功）
     */
    @Update("""
            UPDATE stock 
            SET available_stock = available_stock - #{quantity},
                version = version + 1,
                update_time = now()
            WHERE id = #{skuId}
              AND version = #{version}
              AND available_stock >= #{quantity}
            """)
    int deductStockWithOptimisticLock(
            @Param("skuId") Long skuId,
            @Param("quantity") Integer quantity,
            @Param("version") Integer version
    );

    /**
     * 库存补偿（乐观锁版本）
     *
     * @param skuId SKU ID
     * @param quantity 补偿数量
     * @param version 当前版本号
     * @return 更新行数（0表示更新失败，1表示更新成功）
     */
    @Update("""
            UPDATE stock 
            SET available_stock = available_stock + #{quantity},
                version = version + 1,
                update_time = now()
            WHERE id = #{skuId}
              AND version = #{version}
            """)
    int compensateStockWithOptimisticLock(
            @Param("skuId") Long skuId,
            @Param("quantity") Integer quantity,
            @Param("version") Integer version
    );
}
