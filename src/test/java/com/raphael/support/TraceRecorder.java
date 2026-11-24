package com.raphael.support;

import com.raphael.core.SimulationObserver;
import com.raphael.domain.Message;
import java.util.ArrayList;
import java.util.List;

/** 검증을 위해 실행 1회의 전체 디스패치·전달 추적을 기록한다. */
public final class TraceRecorder implements SimulationObserver {

  /** 기록된 생명주기 단계 하나. */
  public record Step(Message message, long time) {
  }

  public final List<Step> arrivals = new ArrayList<>();
  public final List<Step> dispatches = new ArrayList<>();
  public final List<Step> deliveries = new ArrayList<>();

  @Override
  public void onArrival(Message message, long now) {
    arrivals.add(new Step(message, now));
  }

  @Override
  public void onDispatch(Message message, long now) {
    dispatches.add(new Step(message, now));
  }

  @Override
  public void onDelivery(Message message, long now) {
    deliveries.add(new Step(message, now));
  }

  public long[] dispatchTimes() {
    return dispatches.stream().mapToLong(Step::time).toArray();
  }

  public List<Long> dispatchedIds() {
    return dispatches.stream().map(step -> step.message().id()).toList();
  }

  public List<String> dispatchedTenants() {
    return dispatches.stream().map(step -> step.message().tenantId()).toList();
  }
}
