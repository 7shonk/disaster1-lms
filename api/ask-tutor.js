// api/ask-tutor.js
export default async function handler(req, res) {
    // 開啟跨域放行
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Methods', 'POST, OPTIONS');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

    if (req.method === 'OPTIONS') {
        return res.status(200).end();
    }

    if (req.method !== 'POST') {
        return res.status(405).json({ error: "只支援 POST 請求" });
    }

    const { question, mode } = req.body;
    
    if (!question || !question.trim()) {
        return res.status(400).json({ error: "請輸入您的問題喔！" });
    }

    const GEMINI_API_KEY = process.env.GEMINI_API_KEY;

    if (!GEMINI_API_KEY) {
        return res.status(500).json({ error: "雲端環境變數未設定，請確認 Vercel 後台" });
    }

    const contextMap = {
        "earthquake": "根據中華民國內政部消防署《防震手冊》，地震時黃金守則是「趴下、掩護、穩住」。網路流傳的「黃金三角」或「生命三角」是錯誤且危險的資訊，應在堅固桌下保護頭頸。震後第一時間應關閉瓦斯總開關、切斷火源，防止發生次生火災。櫥櫃等重型家具應使用L型金屬片或黏貼防震帶固定於牆壁。",
        "typhoon": "根據消防署《颱洪災害防救手冊》，防颱準備包括陽台盆栽搬入室內、清理住家排水溝與陽台落水孔以防積水。氣象署澄清，窗戶玻璃貼X型膠帶完全無法增強抗風壓能力，僅能在碎裂時防碎玻璃飛散。低窪或土石流潛勢區居民應配合政府預防性疏散撤離，備妥3天份緊急避難包物資（如水、乾糧、手電筒）。",
        "fire": "根據消防署《火災預防與應變手冊》，濃煙是火場首要殺手。若門外充滿濃煙，切勿往上逃生，應關閉房門阻擋煙霧，並用衣物塞住門縫就地避難。網路謠傳用濕毛巾摀口鼻已證實為錯誤觀念，因為毛巾無法防禦一氧化碳等有毒氣體，且尋找或沾濕毛巾會耽誤逃生黃金時間。住宅應裝設住宅用火災警報器（住警器）。",
        "home-safety": "根據居家安全手冊，瓦斯熱水器必須裝設在通風良好的戶外陽台。若裝在室內或加裝窗戶的陽台，必須安裝強制排氣管以防範一氧化碳中毒。浴室地板應使用防滑貼片、鋪設防滑墊防跌倒。插座切勿超載使用，並定期清理灰塵防積汙導電火災。",
        "first-aid": "根據消防署民眾急救教材，目擊他人突然倒地且失去意識無呼吸時，應立即啟動「叫叫壓電」：叫傷患確認意識、叫旁人打119並取得AED、開始按壓胸部（CPR，速率每分鐘100-120次，按壓深度5-6公分），並依AED指示進行電擊。手指或肢體出血應優先使用「直接壓迫止血法」，不可亂塗藥粉。"
    };

    const selectedMode = mode || 'earthquake';
    const context = contextMap[selectedMode] || contextMap['earthquake'];

    const prompt = `你是一位對齊台灣消防署官方防災指引的 AI 防災導師，語氣親切溫柔且專業。
    
    主題模式：${selectedMode}
    官方指引背景知識：${context}
    
    學生提問：${question}
    
    答題規範：
    1. 請針對學生的提問進行解答。
    2. 若學生的提問與當前主題無關（例如問天氣、無意義亂碼、或閒聊），請溫柔地提醒並引導他回到防災與準備的主題，切勿胡亂回答或敷衍。
    3. 字數請控制在 150 到 200 字以內，使用純文字，不要夾帶任何 Markdown 語法（例如 ** 或 ##），若要換行請直接換行。`;

    try {
        const response = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=${GEMINI_API_KEY}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ contents: [{ parts: [{ text: prompt }] }] })
        });

        const data = await response.json();
        const rawText = data.candidates[0].content.parts[0].text.trim();
        const cleanText = rawText.replace(/```json/g, '').replace(/```/g, '').trim();
        
        return res.status(200).json({ answer: cleanText });
    } catch (error) {
        return res.status(500).json({ error: "AI問答失敗", details: error.message });
    }
}
