package com.rusefi.ldmp;

import com.rusefi.ReaderProvider;
import com.rusefi.output.SdCardFieldsContent;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.LinkedHashMap;
import java.util.List;

import static com.rusefi.AssertCompatibility.assertEquals;

public class LiveDataProcessorTest {
    @Test
    public void testTwoSections() throws IOException {
        String testYaml = "Usages:\n" +
                "  - name: wbo_channels\n" +
                "    java: TsOutputs.java\n" +
                "    folder: console/binary\n" +
                "    cppFileName: status_loop\n" +
                "    output_name: [ \"wb1\", \"wb2\" ]\n" +
                "    constexpr: [\"engine->wbo1\", \"engine->wbo2\"]\n" +
        "#  output_channels always goes first at least because it has protocol version at well-known offset\n" +
                "  - name: output_channels\n" +
                "    java: TsOutputs.java\n" +
                "    folder: console/binary\n" +
                "    cppFileName: status_loop\n" +
                "    constexpr: \"engine->outputChannels\"\n"
                ;


        List<LinkedHashMap> data = LiveDataProcessor.getStringObjectMap(new StringReader(testYaml));

        TestFileCaptor captor = new TestFileCaptor();
        String destinationFolder = "./";
        LiveDataProcessor liveDataProcessor = new LiveDataProcessor("test", new ReaderProvider() {
            @Override
            public Reader read(String fileName) {
                System.out.println("read " + fileName);
                if (fileName.contains("output_channels")) {
                    return new StringReader("struct_no_prefix output_state_s\n" +
                            "\tuint16_t oootempC;Temperature;\"C\", 1, 0, 500, 1000, 0\n" +
                            "\tuint16_t oooesr;ESR;\"ohm\", 1, 0, 0, 10000, 0\n" +
                        "\tstruct LuaAdjustments\n" +
                        "\t\tfloat fuelMult;Lua: Fuel mult;\n" +
                        "\n" +
                        "\t\tbit clutchUpState\n" +
                        "\t\tbit brakePedalState\n" +
                        "\t\tbit disableDecelerationFuelCutOff\n" +
                        "\t\tbit torqueReductionState\n" +
                        "\tend_struct\n" +
                        "LuaAdjustments lua\n" +
                            "end_struct");
                } else {
                    return new StringReader("struct_no_prefix wideband_state_s\n" +
                            "\tuint16_t tempC;WBO: Temperature;\"C\", 1, 0, 500, 1000, 0, \"cate\"\n" +
                            "bit bitName1\n" +
                            "bit bitName2\n" +
                            "\tuint16_t esr;WBO: ESR;\"ohm\", 1, 0, 0, 10000, 0\n" +
                            "end_struct");

                }
            }
        }, captor, destinationFolder);
        liveDataProcessor.handleYaml(data);
        assertEquals(14, captor.fileCapture.size());

        captor.assertOutput("wb1tempC = scalar, U16, 0, \"C\", 1, 0\n" +
            "wb1bitName1 = bits, U32, 4, [0:0]\n" +
            "wb1bitName2 = bits, U32, 4, [1:1]\n" +
            "wb1esr = scalar, U16, 8, \"ohm\", 1, 0\n" +
            "; total TS size = 12\n" +
            "wb2tempC = scalar, U16, 12, \"C\", 1, 0\n" +
            "wb2bitName1 = bits, U32, 16, [0:0]\n" +
            "wb2bitName2 = bits, U32, 16, [1:1]\n" +
            "wb2esr = scalar, U16, 20, \"ohm\", 1, 0\n" +
            "; total TS size = 24\n" +
            "oootempC = scalar, U16, 24, \"C\", 1, 0\n" +
            "oooesr = scalar, U16, 26, \"ohm\", 1, 0\n" +
            "lua_fuelMult = scalar, F32, 28, \"\", 1, 0\n" +
            "lua_clutchUpState = bits, U32, 32, [0:0]\n" +
            "lua_brakePedalState = bits, U32, 32, [1:1]\n" +
            "lua_disableDecelerationFuelCutOff = bits, U32, 32, [2:2]\n" +
            "lua_torqueReductionState = bits, U32, 32, [3:3]\n" +
            "; total TS size = 36\n", liveDataProcessor.getOutputsSectionFileName());

        captor.assertOutput("entry = wb1tempC, \"wb1WBO: Temperature\", int,    \"%d\"\n" +
            "entry = wb1bitName1, \"wb1bitName1\", int,    \"%d\"\n" +
            "entry = wb1bitName2, \"wb1bitName2\", int,    \"%d\"\n" +
            "entry = wb1esr, \"wb1WBO: ESR\", int,    \"%d\"\n" +
            "entry = wb2tempC, \"wb2WBO: Temperature\", int,    \"%d\"\n" +
            "entry = wb2bitName1, \"wb2bitName1\", int,    \"%d\"\n" +
            "entry = wb2bitName2, \"wb2bitName2\", int,    \"%d\"\n" +
            "entry = wb2esr, \"wb2WBO: ESR\", int,    \"%d\"\n" +
            "entry = oootempC, \"Temperature\", int,    \"%d\"\n" +
            "entry = oooesr, \"ESR\", int,    \"%d\"\n" +
            "entry = lua_fuelMult, \"Lua: Fuel mult\", float,  \"%.3f\"\n" +
            "entry = lua_clutchUpState, \"lua_clutchUpState\", int,    \"%d\"\n" +
            "entry = lua_brakePedalState, \"lua_brakePedalState\", int,    \"%d\"\n" +
            "entry = lua_disableDecelerationFuelCutOff, \"lua_disableDecelerationFuelCutOff\", int,    \"%d\"\n" +
            "entry = lua_torqueReductionState, \"lua_torqueReductionState\", int,    \"%d\"\n", liveDataProcessor.getDataLogFileName());


        captor.assertOutput("// generated by gen_live_documentation.sh / LiveDataProcessor.java\n" +
            "decl_frag<wbo_channels_s, 0>{},\t// wb1\n" +
            "decl_frag<wbo_channels_s, 1>{},\t// wb2\n" +
            "decl_frag<output_channels_s>{},\n", liveDataProcessor.getDataFragmentsH());

        captor.assertOutput("indicatorPanel = wbo_channels0IndicatorPanel, 2\n" +
            "\tindicator = {wb1bitName1}, \"bitName1 No\", \"bitName1 Yes\"\n" +
            "\tindicator = {wb1bitName2}, \"bitName2 No\", \"bitName2 Yes\"\n" +
            "\n" +
            "dialog = wbo_channels0Dialog, \"wbo_channels0\"\n" +
            "\tpanel = wbo_channels0IndicatorPanel\n" +
            "\tliveGraph = wbo_channels0_1_Graph, \"Graph\", South\n" +
            "\t\tgraphLine = wb1tempC\n" +
            "\t\tgraphLine = wb1esr\n" +
            "\n" +
            "indicatorPanel = wbo_channels1IndicatorPanel, 2\n" +
            "\tindicator = {wb2bitName1}, \"bitName1 No\", \"bitName1 Yes\"\n" +
            "\tindicator = {wb2bitName2}, \"bitName2 No\", \"bitName2 Yes\"\n" +
            "\n" +
            "dialog = wbo_channels1Dialog, \"wbo_channels1\"\n" +
            "\tpanel = wbo_channels1IndicatorPanel\n" +
            "\tliveGraph = wbo_channels1_1_Graph, \"Graph\", South\n" +
            "\t\tgraphLine = wb2tempC\n" +
            "\t\tgraphLine = wb2esr\n" +
            "\n" +
            "indicatorPanel = output_channelsIndicatorPanel, 2\n" +
            "\tindicator = {lua_clutchUpState}, \"clutchUpState No\", \"clutchUpState Yes\"\n" +
            "\tindicator = {lua_brakePedalState}, \"brakePedalState No\", \"brakePedalState Yes\"\n" +
            "\tindicator = {lua_disableDecelerationFuelCutOff}, \"disableDecelerationFuelCutOff No\", \"disableDecelerationFuelCutOff Yes\"\n" +
            "\tindicator = {lua_torqueReductionState}, \"torqueReductionState No\", \"torqueReductionState Yes\"\n" +
            "\n" +
            "dialog = output_channelsDialog, \"output_channels\"\n" +
            "\tpanel = output_channelsIndicatorPanel\n" +
            "\tliveGraph = output_channels_1_Graph, \"Graph\", South\n" +
            "\t\tgraphLine = oootempC\n" +
            "\t\tgraphLine = oooesr\n" +
            "\t\tgraphLine = lua_fuelMult\n" +
            "\n", liveDataProcessor.getLiveDataIniFileName());

        captor.assertOutput("\t\t\tsubMenu = wbo_channels0Dialog, \"wbo_channels0\"\n" +
            "\t\t\tsubMenu = wbo_channels1Dialog, \"wbo_channels1\"\n" +
            "\t\t\tsubMenu = output_channelsDialog, \"output_channels\"\n", liveDataProcessor.getFancyMenuIni());

        captor.assertOutput("\tgaugeCategory = \"cate\"\n" +
                "wb1tempCGauge = wb1tempC,\"wb1 WBO: Temperature\", \"C\", 500.0,1000.0, 500.0,1000.0, 500.0,1000.0, 0,0\n" +
                "wb2tempCGauge = wb2tempC,\"wb2 WBO: Temperature\", \"C\", 500.0,1000.0, 500.0,1000.0, 500.0,1000.0, 0,0\n",
            liveDataProcessor.getGauges());

        captor.assertOutput("// generated by class com.rusefi.output.SdCardFieldsContent\n" +
                "#include \"board_lookup.h\"\n" +
                "static const LogField fields[] = {\n" +
                "{packedTime, GAUGE_NAME_TIME, \"sec\", 0},\n" +
                "\t{engine->wbo1.tempC, \"wb1WBO: Temperature\", \"C\", 0, \"cate\"},\n" +
                "\t{engine->wbo1, 4, 0, \"wb1bitName1\", \"\"},\n" +
                "\t{engine->wbo1, 4, 1, \"wb1bitName2\", \"\"},\n" +
                "\t{engine->wbo1.esr, \"wb1WBO: ESR\", \"ohm\", 0},\n" +
                "\t{engine->wbo2.tempC, \"wb2WBO: Temperature\", \"C\", 0, \"cate\"},\n" +
                "\t{engine->wbo2, 4, 0, \"wb2bitName1\", \"\"},\n" +
                "\t{engine->wbo2, 4, 1, \"wb2bitName2\", \"\"},\n" +
                "\t{engine->wbo2.esr, \"wb2WBO: ESR\", \"ohm\", 0},\n" +
                "\t{engine->outputChannels.oootempC, \"Temperature\", \"C\", 0},\n" +
                "\t{engine->outputChannels.oooesr, \"ESR\", \"ohm\", 0},\n" +
                "\t{engine->outputChannels.lua.fuelMult, \"Lua: Fuel mult\", \"\", 0},\n" +
                "\t{engine->outputChannels, 8, 0, \"lua.clutchUpState\", \"\"},\n" +
                "\t{engine->outputChannels, 8, 1, \"lua.brakePedalState\", \"\"},\n" +
                "\t{engine->outputChannels, 8, 2, \"lua.disableDecelerationFuelCutOff\", \"\"},\n" +
                "\t{engine->outputChannels, 8, 3, \"lua.torqueReductionState\", \"\"},\n" +
                "};\n",
            destinationFolder + SdCardFieldsContent.SD_CARD_OUTPUT_FILE_NAME);

        captor.assertOutput("// generated by gen_live_documentation.sh / LiveDataProcessor.java\n" +
            "#pragma once\n" +
            "\n" +
            "// this generated C header is mostly used as input for java code generation\n" +
            "typedef enum {\n" +
            "LDS_wbo_channels0,\n" +
            "LDS_wbo_channels1,\n" +
            "LDS_output_channels,\n" +
            "} live_data_e;\n" +
            "#define WBO_CHANNELS_BASE_ADDRESS 0\n" +
            "#define OUTPUT_CHANNELS_BASE_ADDRESS 24\n", liveDataProcessor.getEnumContentFileName());
    }
}
