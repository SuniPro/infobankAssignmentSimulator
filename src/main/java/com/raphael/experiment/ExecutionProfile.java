package com.raphael.experiment;

import java.util.OptionalLong;

/**
 * 실행 1회의 비결정적 실행 측정값이다. 결정성 검사가 시뮬레이션 결과만 비교하도록
 * {@link com.raphael.metrics.SimulationResult}와 분리해 둔다.
 *
 * @param wallClockNanos {@link System#nanoTime()}으로 잰 {@code engine.run()}의 경과 시간
 * @param threadCpuNanos {@link java.lang.management.ThreadMXBean}으로 잰 {@code run()} 동안
 *     시뮬레이션 스레드의 CPU 시간. JVM이 지원하지 않으면 empty
 * @param schedulerElapsedNanos 스케줄러 호출 내부에서 소요된 경과 시간(CPU 시간 아님)의 합
 * @param schedulerTimedCalls 시간을 잰 스케줄러 호출 수(enqueue와 선택 호출의 합)
 * @param timerBiasNanosPerCall 빈 {@code nanoTime()} 구간을 잰 값의 보정된 평균.
 *     {@code schedulerTimedCalls * timerBiasNanosPerCall}은 {@code schedulerElapsedNanos} 중
 *     스케줄러 작업이 아니라 측정 부산물인 부분이 얼마인지를 추정한다.
 */
public record ExecutionProfile(
    long wallClockNanos,
    OptionalLong threadCpuNanos,
    long schedulerElapsedNanos,
    long schedulerTimedCalls,
    double timerBiasNanosPerCall) {

  /** 추정된 타이머 편향을 제거한 스케줄러 경과 시간(하한 0). */
  public double schedulerElapsedNanosCorrected() {
    return Math.max(0.0, schedulerElapsedNanos - schedulerTimedCalls * timerBiasNanosPerCall);
  }
}
