package com.umicorp.autolotto720.dhlottery

/**
 * 연금복권720+ 온라인 구매/내역 기능 게이트.
 *
 * 온라인 구매(el.dhlottery.co.kr/game/pension720/game.jsp + pension.js)는 645와 달리 클라이언트
 * 사이드 AES 암호화 서브시스템이고, 내역 상세(연금복권720+ 원장 상세 엔드포인트·`ltGdsNm` 문자열)도
 * 테스트 계정에 720 구매 실적이 없어 캡처하지 못했다(720-api-contract.md §4~5). 부분 이해 상태의
 * 블라인드 실결제는 머니패스 안전 원칙상 금지이므로 계약을 브라우저 캡처로 확보하기 전까지 false로
 * 둔다 — 캡처 완료 시 이 값만 true로 뒤집는다.
 */
object Feature720 {
    // 실서버 E2E 검증 완료(자동/반자동/수동/폴백 + 내역) → 스케줄 무인 자동구매 무장.
    // 사용자 명시 동의로 게이트 오픈(기존 '참관 하 1건씩' 원칙 해제). 워커의 회차 멱등·판매마감·비재시도 가드 유효.
    const val PURCHASE_ENABLED = true
}
