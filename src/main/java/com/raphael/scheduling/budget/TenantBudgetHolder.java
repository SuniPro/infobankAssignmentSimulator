package com.raphael.scheduling.budget;

/** 테넌트 전역 예산을 가진 정책. 실행기가 독립 예산 감사를 켜는 데 쓴다. */
public interface TenantBudgetHolder {

  /** 테넌트 예산, 없으면 {@code null}. */
  TenantBudget tenantBudget();
}
