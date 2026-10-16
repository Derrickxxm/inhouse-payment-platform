package com.example.payplat.payment;

public record ReconDiff(ReconDiffType type, String channelId, long orderId,
                        Long localAmount, Long channelAmount, String localStatus, String channelStatus) {}
