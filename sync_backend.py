import time
import datetime
import threading
import json
import requests
import sys
import io

# 確保 Windows 終端機輸出不會因為 emoji 編碼錯誤而崩潰
if sys.stdout.encoding != 'utf-8':
    try:
        sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
    except Exception:
        pass

from flask import Flask, request, jsonify
from flask_cors import CORS
from supabase import create_client, Client

# ==========================================
# 1. 基礎金鑰與連線初始化
# ==========================================
SUPABASE_URL = "https://ovyygcsuiwwbgoywplbq.supabase.co"
SUPABASE_KEY = "sb_publishable_Rfekybrmzpc7rjaHAq5Q8Q_ChnHYcAI" 
supabase: Client = create_client(SUPABASE_URL, SUPABASE_KEY)

# 🔒 你的神聖 Gemini 金鑰安全地鎖在 Python 後端，GitHub 上的機器人絕對找不到它！
GEMINI_API_KEY = "AIzaSyB0nqEdU_txzStkTBfhK95E5qbWA6YXpRw"

# ==========================================
# 2. 建立 Flask API 伺服器 (供網頁點擊索取題目)
# ==========================================
app = Flask(__name__)
CORS(app) # 允許跨網域存取，手機連過來也能通

@app.route('/get_quiz', methods=['GET'])
def get_quiz_from_gemini():
    mode = request.args.get('mode', 'earthquake')
    
    # 建立精準 Prompt
    desc_map = {
        "earthquake": "情境：深夜突然發生規模 6.5 強震，屋內停電且物品散落。請針對【地震當下應變】或【摸黑尋找與使用避難包】設計一道引導式情境單選題。",
        "typhoon": "情境：強烈颱風登陸，你家已經停水停電進入第二天，外面風雨交加無法出門。請針對【長期停水電的物資分配】或【防颱居家準備不足時的補救】設計一道引導式情境單選題。",
        "fire": "情境：你住的大樓深夜發生火警，門外樓梯間已經充滿濃煙。請針對【火場逃生決策】或【如何運用身邊物資防煙】設計一道引導式情境單選題。",
        "home-safety": "情境：你正在進行居家安全大檢修，發現熱水器裝在通風不良的室內陽台，且家中浴室地板濕滑無防滑措施。請針對【一氧化碳中毒防範】或【居家防跌倒/防墜落安全預防】設計一道引導式情境單選題。",
        "first-aid": "情境：在路邊目擊一位路人突然倒地、失去意識且沒有呼吸。或者有人切菜時手指大出血。請針對【CPR與AED使用關鍵步驟】或【正確的止血與包紮急救法】設計一道引導式情境單選題。"
    }
    
    # 預防無效的模式
    if mode not in desc_map:
        mode = "earthquake"
        
    prompt = f"""你是一位防災教育專家，負責進行「斷點引導式教學」。
    {desc_map.get(mode)}
    請務必回傳嚴格的標準 JSON 格式，不要夾帶 Markdown 的 ```json 字樣：
    {{
      "question": "情境敘述 + 題目",
      "options": ["選項A", "選項B", "選項C", "選項D"],
      "correctAnswer": "正確選項的完整文字",
      "explanation": "請先告知使用者他目前的觀念哪裡卡住了，然後給予正確的防護觀念指導。"
    }}"""

    try:
        # 從 Python 本機端直接向 Google 發起強而有力的請求
        url = f"https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key={GEMINI_API_KEY}"
        payload = { "contents": [{ "parts": [{ "text": prompt }] }] }
        headers = { "Content-Type": "application/json" }
        
        res = requests.post(url, json=payload, headers=headers)
        res_data = res.json()
        
        raw_text = res_data['candidates'][0]['content']['parts'][0]['text']
        # 清除可能夾帶的 markdown
        clean_json_text = raw_text.replace("```json", "").replace("```", "").strip()
        quiz_json = json.loads(clean_json_text)
        
        return jsonify(quiz_json)
    except Exception as e:
        print(f"❌ Gemini 生成失敗: {e}")
        return jsonify({"error": "AI生成失敗"}), 500

@app.route('/ask_tutor', methods=['POST'])
def ask_tutor_from_gemini():
    try:
        data = request.get_json() or {}
    except Exception:
        data = {}
    question = data.get('question', '').strip()
    mode = data.get('mode', 'earthquake')
    
    if not question:
        return jsonify({"answer": "請輸入您的問題喔！"}), 400
        
    context_map = {
        "earthquake": "根據中華民國內政部消防署《防震手冊》，地震時黃金守則是「趴下、掩護、穩住」。網路流傳的「黃金三角」或「生命三角」是錯誤且危險的資訊，應在堅固桌下保護頭頸。震後第一時間應關閉瓦斯總開關、切斷火源，防止發生次生火災。櫥櫃等重型家具應使用L型金屬片或黏貼防震帶固定於牆壁。",
        "typhoon": "根據消防署《颱洪災害防救手冊》，防颱準備包括陽台盆栽搬入室內、清理住家排水溝與陽台落水孔以防積水。氣象署澄清，窗戶玻璃貼X型膠帶完全無法增強抗風壓能力，僅能在碎裂時防碎玻璃飛散。低窪或土石流潛勢區居民應配合政府預防性疏散撤離，備妥3天份緊急避難包物資（如水、乾糧、手電筒）。",
        "fire": "根據消防署《火災預防與應變手冊》，濃煙是火場首要殺手。若門外充滿濃煙，切勿往上逃生，應關閉房門阻擋煙霧，並用衣物塞住門縫就地避難。網路謠傳用濕毛巾摀口鼻已證實為錯誤觀念，因為毛巾無法防禦一氧化碳等有毒氣體，且尋找或沾濕毛巾會耽誤逃生黃金時間。住宅應裝設住宅用火災警報器（住警器）。",
        "home-safety": "根據居家安全手冊，瓦斯熱水器必須裝設在通風良好的戶外陽台。若裝在室內或加裝窗戶的陽台，必須安裝強制排氣管以防範一氧化碳中毒。浴室地板應使用防滑貼片、鋪設防滑墊防跌倒。插座切勿超載使用，並定期清理灰塵防積汙導電火災。",
        "first-aid": "根據消防署民眾急救教材，目擊他人突然倒地且失去意識無呼吸時，應立即啟動「叫叫壓電」：叫傷患確認意識、叫旁人打119並取得AED、開始按壓胸部（CPR，速率每分鐘100-120次，按壓深度5-6公分），並依AED指示進行電擊。手指或肢體出血應優先使用「直接壓迫止血法」，不可亂塗藥粉。"
    }
    
    prompt = f"""你是一位對齊台灣消防署官方防災指引的 AI 防災導師，語氣親切溫柔且專業。
    
    主題模式：{mode}
    官方指引背景知識：{context_map.get(mode, context_map['earthquake'])}
    
    學生提問：{question}
    
    答題規範：
    1. 請針對學生的提問進行解答。
    2. 若學生的提問與當前主題無關（例如問天氣、無意義亂碼、或閒聊），請溫柔地提醒並引導他回到防災與準備的主題，切勿胡亂回答或敷衍。
    3. 字數請控制在 150 到 200 字以內，使用純文字，不要夾帶任何 Markdown 語法（例如 ** 或 ##），若要換行請直接換行。
    """

    try:
        url = f"https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key={GEMINI_API_KEY}"
        payload = { "contents": [{ "parts": [{ "text": prompt }] }] }
        headers = { "Content-Type": "application/json" }
        
        res = requests.post(url, json=payload, headers=headers)
        res_data = res.json()
        
        answer = res_data['candidates'][0]['content']['parts'][0]['text'].strip()
        # 移除可能夾帶的 markdown 區塊
        answer = answer.replace("```json", "").replace("```", "").strip()
        return jsonify({"answer": answer})
    except Exception as e:
        print(f"❌ AI 導師回答失敗: {e}")
        return jsonify({"answer": "AI 導師目前連線有些忙碌，請稍後再試喔！"}), 500

if __name__ == "__main__":
    # 本地啟動 API 伺服器，對外窗口開在 port 5000
    print("🚀 【全能防災單引擎中控台】上線！")
    print("💡 網頁點擊出題時，會直接透過本機電腦向 Google 要資料，穩如泰山！")
    app.run(host='0.0.0.0', port=5000)