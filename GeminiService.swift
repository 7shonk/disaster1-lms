import Foundation

// 1. 定義接收 Gemini JSON 的資料結構 (對應 AI 吐出來的格式)
struct QuizQuestion: Codable {
    let question: String
    let options: [String]
    let correctAnswer: String
    let explanation: String
}

class GeminiService {
    // ⚠️ 記得去 Google AI Studio 申請一把免費的 Gemini API Key 放這裡
    let apiKey = "AIzaSy_請換成你申請的_Gemini_API_Key" 
    
    // 2. 呼叫 Gemini 1.5 Flash 產生題目的非同步函式
    func generateDisasterQuiz(topic: String) async throws -> QuizQuestion {
        let urlString = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=\(apiKey)"
        guard let url = URL(string: urlString) else { throw URLError(.badURL) }
        
        // 這是你身為 Prompt Engineer 的靈魂！
        let prompt = """
        你是一位台灣消防署的防災教育專家。請針對主題【\(topic)】智慧生成一道繁體中文的四選一單選題。
        請務必回傳嚴格的標準 JSON 格式，不要夾帶任何 Markdown 標籤 (不要有 ```json)：
        {
          "question": "題目敘述",
          "options": ["選項A", "選項B", "選項C", "選項D"],
          "correctAnswer": "正確選項的完整文字",
          "explanation": "請詳細解釋為什麼這個答案正確，以及其他選項錯在哪裡，用於學生答錯時的糾正指導。"
        }
        """
        
        // 組合 Request Body (包含強制 JSON 輸出的設定)
        let requestBody: [String: Any] = [
            "contents": [
                ["parts": [ ["text": prompt] ]]
            ],
            // 💡 關鍵防呆：強迫 Gemini 進入 JSON Mode
            "generationConfig": [
                "responseMimeType": "application/json"
            ]
        ]
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: requestBody)
        
        // 發送請求給 Gemini
        let (data, _) = try await URLSession.shared.data(for: request)
        
        // 解析第一層 API 回應
        let jsonResponse = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        guard let candidates = jsonResponse?["candidates"] as? [[String: Any]],
              let content = candidates.first?["content"] as? [String: Any],
              let parts = content["parts"] as? [[String: Any]],
              let jsonString = parts.first?["text"] as? String else {
            throw NSError(domain: "GeminiError", code: 1, userInfo: [NSLocalizedDescriptionKey: "API 回應格式解析失敗"])
        }
        
        // 將 AI 吐出來的 JSON 字串，轉換成我們的 QuizQuestion 結構
        guard let jsonData = jsonString.data(using: .utf8) else {
            throw NSError(domain: "GeminiError", code: 2, userInfo: [NSLocalizedDescriptionKey: "字串轉 Data 失敗"])
        }
        
        let quiz = try JSONDecoder().decode(QuizQuestion.self, from: jsonData)
        return quiz
    }
}