package app.aaps.plugins.main.general.overview

import android.content.Context
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import app.aaps.core.data.model.GV
import app.aaps.core.data.time.T
import app.aaps.core.graph.data.BarGraphSeries
import app.aaps.core.graph.data.DataPointWithLabelInterface
import app.aaps.core.graph.data.DeviationDataPoint
import app.aaps.core.graph.data.FixedLineGraphSeries
import app.aaps.core.graph.data.LineGraphSeries
import app.aaps.core.graph.data.PointsWithLabelGraphSeries
import app.aaps.core.graph.data.ScaledDataPoint
import app.aaps.core.graph.data.StepsDataPoint
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.graph.Scale
import app.aaps.core.interfaces.graph.SeriesData
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.Preferences
import app.aaps.core.objects.R
import app.aaps.core.objects.extensions.convertedToPercent
import app.aaps.core.objects.extensions.isInProgress
import app.aaps.core.objects.extensions.toStringFull
import app.aaps.core.objects.extensions.toStringShort
import com.jjoe64.graphview.series.DataPoint
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OverviewDataImpl @Inject constructor(
    private val rh: ResourceHelper,
    private val dateUtil: DateUtil,
    private val sp: SP,
    private val activePlugin: ActivePlugin,
    private val profileFunction: ProfileFunction,
    private val persistenceLayer: PersistenceLayer,
    private val processedTbrEbData: ProcessedTbrEbData,
    private val preferences: Preferences,
    private val profileUtil: ProfileUtil,
    private val processedDeviceStatusData: ProcessedDeviceStatusData,
    private val config: Config,
    private val aapsLogger: AAPSLogger,
    private val constraintsChecker: ConstraintsChecker
) : OverviewData {

    override var rangeToDisplay = 6 // for graph
    override var toTime: Long = 0
    override var fromTime: Long = 0
    override var endTime: Long = 0

    override fun reset() {
        pumpStatus = ""
        calcProgressPct = 100
        bgReadingsArray = ArrayList()
        maxBgValue = Double.MIN_VALUE
        bucketedGraphSeries = PointsWithLabelGraphSeries<DataPointWithLabelInterface>()
        bgReadingGraphSeries = PointsWithLabelGraphSeries<DataPointWithLabelInterface>()
        predictionsGraphSeries = PointsWithLabelGraphSeries<DataPointWithLabelInterface>()
        baseBasalGraphSeries = LineGraphSeries<ScaledDataPoint>()
        tempBasalGraphSeries = LineGraphSeries<ScaledDataPoint>()
        basalLineGraphSeries = LineGraphSeries<ScaledDataPoint>()
        absoluteBasalGraphSeries = LineGraphSeries<ScaledDataPoint>()
        temporaryTargetSeries = LineGraphSeries<DataPoint>()
        maxIAValue = 0.0
        activitySeries = FixedLineGraphSeries<ScaledDataPoint>()
        activityPredictionSeries = FixedLineGraphSeries<ScaledDataPoint>()
        maxIobValueFound = Double.MIN_VALUE
        iobSeries = FixedLineGraphSeries<ScaledDataPoint>()
        absIobSeries = FixedLineGraphSeries<ScaledDataPoint>()
        iobPredictions1Series = PointsWithLabelGraphSeries<DataPointWithLabelInterface>()
        //iobPredictions2Series = PointsWithLabelGraphSeries()
        maxBGIValue = Double.MIN_VALUE
        minusBgiSeries = FixedLineGraphSeries<ScaledDataPoint>()
        minusBgiHistSeries = FixedLineGraphSeries<ScaledDataPoint>()
        maxCobValueFound = Double.MIN_VALUE
        cobSeries = FixedLineGraphSeries<ScaledDataPoint>()
        cobMinFailOverSeries = PointsWithLabelGraphSeries<DataPointWithLabelInterface>()
        maxDevValueFound = Double.MIN_VALUE
        deviationsSeries = BarGraphSeries<DeviationDataPoint>()
        maxRatioValueFound = 5.0                    //even if sens data equals 0 for all the period, minimum scale is between 95% and 105%
        minRatioValueFound = -maxRatioValueFound
        ratioSeries = LineGraphSeries<ScaledDataPoint>()
        maxFromMaxValueFound = Double.MIN_VALUE
        maxFromMinValueFound = Double.MIN_VALUE
        dsMaxSeries = LineGraphSeries<ScaledDataPoint>()
        dsMinSeries = LineGraphSeries<ScaledDataPoint>()
        maxTreatmentsValue = 0.0
        treatmentsSeries = PointsWithLabelGraphSeries<DataPointWithLabelInterface>()
        maxEpsValue = 0.0
        epsSeries = PointsWithLabelGraphSeries<DataPointWithLabelInterface>()
        maxTherapyEventValue = 0.0
        therapyEventSeries = PointsWithLabelGraphSeries<DataPointWithLabelInterface>()
        heartRateGraphSeries = PointsWithLabelGraphSeries<DataPointWithLabelInterface>()
        stepsCountGraphSeries = PointsWithLabelGraphSeries<StepsDataPoint>()
        maxVarSensValueFound = 200.0
        minVarSensValueFound = 50.0
        varSensSeries = LineGraphSeries<ScaledDataPoint>()
    }

    override fun initRange() {
        rangeToDisplay = sp.getInt(app.aaps.core.utils.R.string.key_rangetodisplay, 6)

        val calendar = Calendar.getInstance().also {
            it.timeInMillis = System.currentTimeMillis()
            it[Calendar.MILLISECOND] = 0
            it[Calendar.SECOND] = 0
            it[Calendar.MINUTE] = 0
            it.add(Calendar.HOUR, 1)
        }

        toTime = calendar.timeInMillis + 100000 // little bit more to avoid wrong rounding - GraphView specific
        fromTime = toTime - T.hours(rangeToDisplay.toLong()).msecs()
        endTime = toTime
    }

    /*
     * PUMP STATUS
     */

    override var pumpStatus: String = ""

    /*
     * CALC PROGRESS
     */

    override var calcProgressPct: Int = 100

    /*
    * TEMPORARY BASAL
    */

    override fun temporaryBasalText(): String =
        profileFunction.getProfile()?.let { profile ->
            var temporaryBasal = processedTbrEbData.getTempBasalIncludingConvertedExtended(dateUtil.now())
            if (temporaryBasal?.isInProgress == false) temporaryBasal = null
            val usePercentage = preferences.get(BooleanKey.OverviewBasalIsAlwaysNotAbsolute)
            temporaryBasal?.toStringShort(usePercentage, profile.getBasal(), rh) ?: rh.gs(app.aaps.core.ui.R.string.pump_base_basal_rate, profile.getBasal())
        } ?: rh.gs(app.aaps.core.ui.R.string.value_unavailable_short)

    override fun temporaryBasalDialogText(): String =
        profileFunction.getProfile()?.let { profile ->
            processedTbrEbData.getTempBasalIncludingConvertedExtended(dateUtil.now())?.let { temporaryBasal ->
                "${rh.gs(app.aaps.core.ui.R.string.base_basal_rate_label)}: ${rh.gs(app.aaps.core.ui.R.string.pump_base_basal_rate, profile.getBasal())}" +
                    "\n" + rh.gs(app.aaps.core.ui.R.string.tempbasal_label) + ": " + temporaryBasal.toStringFull(profile, dateUtil, rh)
            }
                ?: "${rh.gs(app.aaps.core.ui.R.string.base_basal_rate_label)}: ${rh.gs(app.aaps.core.ui.R.string.pump_base_basal_rate, profile.getBasal())}"
        } ?: rh.gs(app.aaps.core.ui.R.string.value_unavailable_short)

    override fun sensitivityText(showIsfForCarbs: Boolean, loop: Loop, iobCobCalculator: IobCobCalculator): String {
        val useAutosens =
            if (config.NSCLIENT) sp.getBoolean(app.aaps.core.utils.R.string.key_used_autosens_on_main_phone, false)
            else constraintsChecker.isAutosensModeEnabled().value()

        val request = loop.lastRun?.request
        val lastAutosensData = iobCobCalculator.ads.getLastAutosensData("Overview", aapsLogger, dateUtil)
        val ratioUsed = request?.autosensResult?.ratio ?: 1.0

        var text = ""
        if (useAutosens) {
            text += if (preferences.get(BooleanKey.ApsDynIsfAdjustSensitivity))
                String.format(Locale.ENGLISH, "%.0f%%", ratioUsed * 100)
            else
                lastAutosensData?.let { String.format(Locale.ENGLISH, "%.0f%%", it.autosensResult.ratio * 100) } ?: ""
        }

        // Show variable sensitivity
        val isfMgdl = profileFunction.getProfile()?.getProfileIsfMgdl()
        val isfForCarbs = profileFunction.getProfile()?.getIsfMgdlForCarbs(dateUtil.now(), "Overview", config, processedDeviceStatusData)
        val variableSens =
            if (config.APS) request?.variableSens ?: 0.0
            else if (config.NSCLIENT) processedDeviceStatusData.getAPSResult()?.variableSens ?: 0.0
            else 0.0
        if (variableSens != 0.0 && isfMgdl != null) {
            if (useAutosens) text += "\n"
            text += if (!showIsfForCarbs || isfForCarbs == null)
                String.format(
                    Locale.getDefault(), "%1$.1f→%2$.1f",
                    profileUtil.fromMgdlToUnits(isfMgdl, profileFunction.getUnits()),
                    profileUtil.fromMgdlToUnits(variableSens, profileFunction.getUnits())
                )
            else
                String.format(
                    Locale.getDefault(), "%1$.1f→%2$.1f (%3$.1f)",
                    profileUtil.fromMgdlToUnits(isfMgdl, profileFunction.getUnits()),
                    profileUtil.fromMgdlToUnits(variableSens, profileFunction.getUnits()),
                    profileUtil.fromMgdlToUnits(isfForCarbs, profileFunction.getUnits())
                )
        }
        return text
    }

    @DrawableRes override fun temporaryBasalIcon(): Int =
        profileFunction.getProfile()?.let { profile ->
            processedTbrEbData.getTempBasalIncludingConvertedExtended(dateUtil.now())?.let { temporaryBasal ->
                val percentRate = temporaryBasal.convertedToPercent(dateUtil.now(), profile)
                when {
                    percentRate > 100 -> R.drawable.ic_cp_basal_tbr_high
                    percentRate < 100 -> R.drawable.ic_cp_basal_tbr_low
                    else              -> R.drawable.ic_cp_basal_no_tbr
                }
            }
        } ?: R.drawable.ic_cp_basal_no_tbr

    @AttrRes override fun temporaryBasalColor(context: Context?): Int =
        processedTbrEbData.getTempBasalIncludingConvertedExtended(dateUtil.now())?.let {
            rh.gac(context, app.aaps.core.ui.R.attr.basal)
        } ?: rh.gac(context, app.aaps.core.ui.R.attr.defaultTextColor)

    /*
     * EXTENDED BOLUS
    */

    override fun extendedBolusText(): String =
        persistenceLayer.getExtendedBolusActiveAt(dateUtil.now())?.let { extendedBolus ->
            if (!extendedBolus.isInProgress(dateUtil)) ""
            else if (!activePlugin.activePump.isFakingTempsByExtendedBoluses) rh.gs(app.aaps.core.ui.R.string.pump_base_basal_rate, extendedBolus.rate)
            else ""
        } ?: ""

    override fun extendedBolusDialogText(): String =
        persistenceLayer.getExtendedBolusActiveAt(dateUtil.now())?.toStringFull(dateUtil, rh) ?: ""

    /*
     * Graphs
     */

    override var bgReadingsArray: List<GV> = ArrayList()
    override var maxBgValue = Double.MIN_VALUE
    override var bucketedGraphSeries: SeriesData = PointsWithLabelGraphSeries<DataPointWithLabelInterface>()
    override var bgReadingGraphSeries: SeriesData = PointsWithLabelGraphSeries<DataPointWithLabelInterface>()
    override var predictionsGraphSeries: SeriesData = PointsWithLabelGraphSeries<DataPointWithLabelInterface>()

    override val basalScale = Scale()
    override var baseBasalGraphSeries: SeriesData = LineGraphSeries<ScaledDataPoint>()
    override var tempBasalGraphSeries: SeriesData = LineGraphSeries<ScaledDataPoint>()
    override var basalLineGraphSeries: SeriesData = LineGraphSeries<ScaledDataPoint>()
    override var absoluteBasalGraphSeries: SeriesData = LineGraphSeries<ScaledDataPoint>()

    override var temporaryTargetSeries: SeriesData = LineGraphSeries<DataPoint>()

    override var maxIAValue = 0.0
    override val actScale = Scale()
    override var activitySeries: SeriesData = FixedLineGraphSeries<ScaledDataPoint>()
    override var activityPredictionSeries: SeriesData = FixedLineGraphSeries<ScaledDataPoint>()

    override var maxEpsValue = 0.0
    override val epsScale = Scale()
    override var epsSeries: SeriesData = PointsWithLabelGraphSeries<DataPointWithLabelInterface>()
    override var maxTreatmentsValue = 0.0
    override var treatmentsSeries: SeriesData = PointsWithLabelGraphSeries<DataPointWithLabelInterface>()
    override var maxTherapyEventValue = 0.0
    override var therapyEventSeries: SeriesData = PointsWithLabelGraphSeries<DataPointWithLabelInterface>()

    override var maxIobValueFound = Double.MIN_VALUE
    override val iobScale = Scale()
    override var iobSeries: SeriesData = FixedLineGraphSeries<ScaledDataPoint>()
    override var absIobSeries: SeriesData = FixedLineGraphSeries<ScaledDataPoint>()
    override var iobPredictions1Series: SeriesData = PointsWithLabelGraphSeries<DataPointWithLabelInterface>()

    override var maxBGIValue = Double.MIN_VALUE
    override val bgiScale = Scale()
    override var minusBgiSeries: SeriesData = FixedLineGraphSeries<ScaledDataPoint>()
    override var minusBgiHistSeries: SeriesData = FixedLineGraphSeries<ScaledDataPoint>()

    override var maxCobValueFound = Double.MIN_VALUE
    override val cobScale = Scale()
    override var cobSeries: SeriesData = FixedLineGraphSeries<ScaledDataPoint>()
    override var cobMinFailOverSeries: SeriesData = PointsWithLabelGraphSeries<DataPointWithLabelInterface>()

    override var maxDevValueFound = Double.MIN_VALUE
    override val devScale = Scale()
    override var deviationsSeries: SeriesData = BarGraphSeries<DeviationDataPoint>()

    override var maxRatioValueFound = 5.0                    //even if sens data equals 0 for all the period, minimum scale is between 95% and 105%
    override var minRatioValueFound = -maxRatioValueFound
    override val ratioScale = Scale()
    override var ratioSeries: SeriesData = LineGraphSeries<ScaledDataPoint>()

    override var maxFromMaxValueFound = Double.MIN_VALUE
    override var maxFromMinValueFound = Double.MIN_VALUE
    override val dsMaxScale = Scale()
    override val dsMinScale = Scale()
    override var dsMaxSeries: SeriesData = LineGraphSeries<ScaledDataPoint>()
    override var dsMinSeries: SeriesData = LineGraphSeries<ScaledDataPoint>()
    override var heartRateScale = Scale()
    override var heartRateGraphSeries: SeriesData = PointsWithLabelGraphSeries<DataPointWithLabelInterface>()
    override var stepsForScale = Scale()
    override var stepsCountGraphSeries: SeriesData = PointsWithLabelGraphSeries<DataPointWithLabelInterface>()

    override var maxVarSensValueFound = 200.0
    override var minVarSensValueFound = 50.0
    override val varSensScale = Scale()
    override var varSensSeries: SeriesData = LineGraphSeries<ScaledDataPoint>()
}
