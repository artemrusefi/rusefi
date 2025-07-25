/**
 * @file    accel_enrichment.cpp
 * @brief   Acceleration enrichment calculator
 *
 * In this file we have three strategies for acceleration/deceleration fuel correction
 *
 * 1) MAP rate-of-change correction
 * 2) TPS rate-of-change correction
 * 3) fuel film/wal wetting correction
 *   AWC Added to Wall Coefficient, %
 *   AWA Added to Wall Amount
 *   SOC Sucked Off wall Coefficient, %
 *   SOA Sucked Off wall amount
 *   WF  current on-Wall Fuel amount
 *
 *
 * http://rusefi.com/wiki/index.php?title=Manual:Software:Fuel_Control
 * @date Apr 21, 2014
 * @author Dmitry Sidin
 * @author Andrey Belomutskiy, (c) 2012-2020
 * @author Matthew Kennedy
 */

#include "pch.h"
#include "accel_enrichment.h"


// on this level we do not distinguish between multiplier and 'ms adder' modes
float TpsAccelEnrichment::getTpsEnrichment() {
	ScopePerf perf(PE::GetTpsEnrichment);
	
	// If predictive MAP mode is active, the old "adder" logic is disabled.
	if (engineConfiguration->accelEnrichmentMode == AE_MODE_PREDICTIVE_MAP) {
		return 0;
	}

	if (engineConfiguration->tpsAccelLookback == 0) {
		// If disabled, return 0.
		return 0;
	}
	float rpm = Sensor::getOrZero(SensorType::Rpm);
	if (rpm == 0) {
		return 0;
	}

	if (isAboveAccelThreshold) {
    valueFromTable = interpolate3d(config->tpsTpsAccelTable,
      config->tpsTpsAccelToRpmBins, tpsTo,
      config->tpsTpsAccelFromRpmBins, tpsFrom
    );

		extraFuel = valueFromTable;
		m_timeSinceAccel.reset();
	} else if (isBelowDecelThreshold) {
		extraFuel = deltaTps * engineConfiguration->tpsDecelEnleanmentMultiplier;
		m_timeSinceAccel.reset();
	} else {
		extraFuel = 0;
	}

	// Fractional enrichment (fuel portions are accumulated and split between several engine cycles.
	// This is a crude imitation of carburetor's acceleration pump.
	isFractionalEnrichment = engineConfiguration->tpsAccelFractionPeriod > 1 || engineConfiguration->tpsAccelFractionDivisor > 1.0f;
	if (isFractionalEnrichment) {
		// make sure both values are non-zero
		float periodF = std::max<int>(engineConfiguration->tpsAccelFractionPeriod, 1);
		float divisor = std::max(engineConfiguration->tpsAccelFractionDivisor, 1.0f);

		// if current extra fuel portion is not "strong" enough, then we keep up the "pump pressure" with the accumulated portion
		floatms_t maxExtraFuel = std::max(extraFuel, accumulatedValue);
		// use only a fixed fraction of the accumulated portion
		fractionalInjFuel = maxExtraFuel / divisor;

		// update max counters
		maxExtraPerCycle = std::max(extraFuel, maxExtraPerCycle);
		maxInjectedPerPeriod = std::max(fractionalInjFuel, maxInjectedPerPeriod);

		// evenly split it between several engine cycles
		extraFuel = fractionalInjFuel / periodF;
	} else {
		resetFractionValues();
	}

	float mult = interpolate2d(rpm, config->tpsTspCorrValuesBins,
						config->tpsTspCorrValues);
	if (mult != 0 && (mult < 0.01 || mult > 100)) {
		mult = 1;
	}

	return extraFuel * mult;
}

void TpsAccelEnrichment::onEngineCycleTps() {
	// we update values in handleFuel() directly by calling onNewValue()

	onUpdateInvocationCounter++;

	// we used some extra fuel during the current cycle, so we "charge" our "acceleration pump" with it
	accumulatedValue -= maxExtraPerPeriod;
	maxExtraPerPeriod = std::max(maxExtraPerCycle, maxExtraPerPeriod);
	maxExtraPerCycle = 0;
	accumulatedValue += maxExtraPerPeriod;

	// update the accumulated value every 'Period' engine cycles
	isTimeToResetAccumulator = --cycleCnt <= 0;
	if (isTimeToResetAccumulator) {
		maxExtraPerPeriod = 0;

		// we've injected this portion during the cycle, so we set what's left for the next cycle
		accumulatedValue -= maxInjectedPerPeriod;
		maxInjectedPerPeriod = 0;

		// it's an infinitely convergent series, so we set a limit at some point
		// (also make sure that accumulatedValue is positive, for safety)
		static const floatms_t smallEpsilon = 0.001f;
		belowEpsilon = accumulatedValue < smallEpsilon;
		if (belowEpsilon) {
			accumulatedValue = 0;
		}

		// reset the counter
		cycleCnt = engineConfiguration->tpsAccelFractionPeriod;
	}
}

int TpsAccelEnrichment::getMaxDeltaIndex() {
	int len = minI(cb.getSize(), cb.getCount());
	tooShort = len < 2;
	if (tooShort)
		return 0;
	int ci = cb.currentIndex - 1;
	float maxValue = cb.get(ci) - cb.get(ci - 1);
	int resultIndex = ci;

	// todo: 'get' method is maybe a bit heavy because of the branching
	// todo: this could be optimized with some careful magic

	for (int i = 1; i<len - 1;i++) {
		float v = cb.get(ci - i) - cb.get(ci - i - 1);
		if (v > maxValue) {
			maxValue = v;
			resultIndex = ci - i;
		}
	}

	return resultIndex;
}

float TpsAccelEnrichment::getMaxDelta() {
	int index = getMaxDeltaIndex();

	return (cb.get(index) - (cb.get(index - 1)));
}

void TpsAccelEnrichment::resetAE() {
	cb.clear();
	resetFractionValues();
}

void TpsAccelEnrichment::resetFractionValues() {
	accumulatedValue = 0;
	maxExtraPerCycle = 0;
	maxExtraPerPeriod = 0;
	maxInjectedPerPeriod = 0;
	cycleCnt = 0;
}

void TpsAccelEnrichment::setLength(int length) {
	cb.setSize(length);
}

void TpsAccelEnrichment::onNewValue(float currentValue) {
	// Push new value in to the history buffer
	cb.add(currentValue);

	// Update deltas
	int maxDeltaIndex = getMaxDeltaIndex();
	tpsFrom = cb.get(maxDeltaIndex - 1);
	tpsTo = cb.get(maxDeltaIndex);
	deltaTps = tpsTo - tpsFrom;

	// Update threshold detection
	isAboveAccelThreshold = deltaTps > engineConfiguration->tpsAccelEnrichmentThreshold;

	// If an acceleration event just happened, latch the flag so it can be read once.
	if (isAboveAccelThreshold) {
		m_accelEventJustOccurred = true;
	}

	// TODO: can deltaTps actually be negative? Will this ever trigger?
	isBelowDecelThreshold = deltaTps < -engineConfiguration->tpsDecelEnleanmentThreshold;
}

bool TpsAccelEnrichment::isAccelEventTriggered() {
	// Read the flag
	bool result = m_accelEventJustOccurred;
	// Reset it so we only fire once per event
	m_accelEventJustOccurred = false;
	return result;
}

TpsAccelEnrichment::TpsAccelEnrichment() {
	resetAE();
	cb.setSize(4);
}

void TpsAccelEnrichment::onConfigurationChange(engine_configuration_s const* /*previousConfig*/) {
	constexpr float slowCallbackPeriodSecond = SLOW_CALLBACK_PERIOD_MS / 1000.0f;
	int length = engineConfiguration->tpsAccelLookback / slowCallbackPeriodSecond;

	if (length < 1) {
		efiPrintf("setTpsAccelLen: Length should be positive [%d]", length);
		return;
	}

	setLength(length);
}

float TpsAccelEnrichment::getTimeSinceAcell() const {
	return m_timeSinceAccel.getElapsedSeconds();
}

void initAccelEnrichment() {

	engine->module<TpsAccelEnrichment>()->onConfigurationChange(nullptr);
}
