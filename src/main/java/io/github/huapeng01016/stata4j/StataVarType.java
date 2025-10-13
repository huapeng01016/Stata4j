package io.github.huapeng01016.stata4j;

/**
 * Represents Stata variable types.
 */
public enum StataVarType {
    BYTE(251),
    INT(252),
    LONG(253),
    FLOAT(254),
    DOUBLE(255),
    
    // String types (1-244 represent string length)
    STR1(1), STR2(2), STR3(3), STR4(4), STR5(5),
    STR6(6), STR7(7), STR8(8), STR9(9), STR10(10),
    STR11(11), STR12(12), STR13(13), STR14(14), STR15(15),
    STR16(16), STR17(17), STR18(18), STR19(19), STR20(20),
    STR21(21), STR22(22), STR23(23), STR24(24), STR25(25),
    STR26(26), STR27(27), STR28(28), STR29(29), STR30(30),
    STR31(31), STR32(32), STR33(33), STR34(34), STR35(35),
    STR36(36), STR37(37), STR38(38), STR39(39), STR40(40),
    STR41(41), STR42(42), STR43(43), STR44(44), STR45(45),
    STR46(46), STR47(47), STR48(48), STR49(49), STR50(50),
    STR51(51), STR52(52), STR53(53), STR54(54), STR55(55),
    STR56(56), STR57(57), STR58(58), STR59(59), STR60(60),
    STR61(61), STR62(62), STR63(63), STR64(64), STR65(65),
    STR66(66), STR67(67), STR68(68), STR69(69), STR70(70),
    STR71(71), STR72(72), STR73(73), STR74(74), STR75(75),
    STR76(76), STR77(77), STR78(78), STR79(79), STR80(80),
    STR81(81), STR82(82), STR83(83), STR84(84), STR85(85),
    STR86(86), STR87(87), STR88(88), STR89(89), STR90(90),
    STR91(91), STR92(92), STR93(93), STR94(94), STR95(95),
    STR96(96), STR97(97), STR98(98), STR99(99), STR100(100),
    STR101(101), STR102(102), STR103(103), STR104(104), STR105(105),
    STR106(106), STR107(107), STR108(108), STR109(109), STR110(110),
    STR111(111), STR112(112), STR113(113), STR114(114), STR115(115),
    STR116(116), STR117(117), STR118(118), STR119(119), STR120(120),
    STR121(121), STR122(122), STR123(123), STR124(124), STR125(125),
    STR126(126), STR127(127), STR128(128), STR129(129), STR130(130),
    STR131(131), STR132(132), STR133(133), STR134(134), STR135(135),
    STR136(136), STR137(137), STR138(138), STR139(139), STR140(140),
    STR141(141), STR142(142), STR143(143), STR144(144), STR145(145),
    STR146(146), STR147(147), STR148(148), STR149(149), STR150(150),
    STR151(151), STR152(152), STR153(153), STR154(154), STR155(155),
    STR156(156), STR157(157), STR158(158), STR159(159), STR160(160),
    STR161(161), STR162(162), STR163(163), STR164(164), STR165(165),
    STR166(166), STR167(167), STR168(168), STR169(169), STR170(170),
    STR171(171), STR172(172), STR173(173), STR174(174), STR175(175),
    STR176(176), STR177(177), STR178(178), STR179(179), STR180(180),
    STR181(181), STR182(182), STR183(183), STR184(184), STR185(185),
    STR186(186), STR187(187), STR188(188), STR189(189), STR190(190),
    STR191(191), STR192(192), STR193(193), STR194(194), STR195(195),
    STR196(196), STR197(197), STR198(198), STR199(199), STR200(200),
    STR201(201), STR202(202), STR203(203), STR204(204), STR205(205),
    STR206(206), STR207(207), STR208(208), STR209(209), STR210(210),
    STR211(211), STR212(212), STR213(213), STR214(214), STR215(215),
    STR216(216), STR217(217), STR218(218), STR219(219), STR220(220),
    STR221(221), STR222(222), STR223(223), STR224(224), STR225(225),
    STR226(226), STR227(227), STR228(228), STR229(229), STR230(230),
    STR231(231), STR232(232), STR233(233), STR234(234), STR235(235),
    STR236(236), STR237(237), STR238(238), STR239(239), STR240(240),
    STR241(241), STR242(242), STR243(243), STR244(244);
    
    private final int code;
    
    StataVarType(int code) {
        this.code = code;
    }
    
    public int getCode() {
        return code;
    }
    
    public boolean isNumeric() {
        return code >= 251 && code <= 255;
    }
    
    public boolean isString() {
        return code >= 1 && code <= 244;
    }
    
    public int getStringLength() {
        if (!isString()) {
            throw new IllegalStateException("Not a string type");
        }
        return code;
    }
    
    public static StataVarType fromCode(int code) throws StataFormatException {
        for (StataVarType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new StataFormatException("Invalid variable type code: " + code);
    }
}
