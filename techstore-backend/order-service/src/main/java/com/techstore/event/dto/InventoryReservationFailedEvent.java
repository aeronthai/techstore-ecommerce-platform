package com.techstore.event.dto;

import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryReservationFailedEvent implements Serializable {
    static final long serialVersionUID = 1L;
    private Long orderId;
    private String reason;
}
